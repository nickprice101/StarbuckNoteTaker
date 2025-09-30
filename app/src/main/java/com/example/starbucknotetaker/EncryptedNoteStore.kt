package com.example.starbucknotetaker

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class EncryptedNoteStore(
    private val context: Context,
    private val attachmentStore: AttachmentStore = AttachmentStore(context),
) {
    private val file = File(context.filesDir, "notes.enc")
    companion object {
        private const val VERSION = 1
    }

    fun loadNotes(pin: String): List<Note> {
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes()
        return loadNotesFromBytes(bytes, pin)
    }

    fun loadNotesFromBytes(bytes: ByteArray, pin: String): List<Note> {
        if (bytes.size < 28) return emptyList()
        val salt = bytes.copyOfRange(0, 16)
        val iv = bytes.copyOfRange(16, 28)
        val cipherText = bytes.copyOfRange(28, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = deriveKey(pin, salt)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val json = String(cipher.doFinal(cipherText), Charsets.UTF_8)
        val root = runCatching { JSONObject(json) }.getOrNull()
        @Suppress("UNUSED_VARIABLE")
        val version = root?.optInt("version", VERSION) ?: VERSION // currently unused but reserved for future migrations
        val arr = when {
            root != null && root.has("notes") -> root.getJSONArray("notes")
            else -> JSONArray(json)
        }
        val notes = mutableListOf<Note>()
        var migrated = false
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val imagesJson = obj.optJSONArray("images") ?: JSONArray()
            val images = mutableListOf<NoteImage>()
            for (j in 0 until imagesJson.length()) {
                when (val entry = imagesJson.get(j)) {
                    is JSONObject -> {
                        val attachmentId = entry.optString("id", null)?.takeIf { it.isNotBlank() }
                        val data = entry.optString("data", null)?.takeIf { it.isNotBlank() }
                        val resolved = resolveImage(attachmentId, data, pin)
                        if (resolved.attachmentId != attachmentId) {
                            migrated = true
                        }
                        images.add(resolved)
                    }
                    is String -> {
                        val base64 = entry
                        val resolved = resolveImage(null, base64, pin)
                        if (resolved.attachmentId != null) {
                            migrated = true
                        }
                        images.add(resolved)
                    }
                    else -> {}
                }
            }
            val filesJson = obj.optJSONArray("files") ?: JSONArray()
            val files = mutableListOf<NoteFile>()
            for (j in 0 until filesJson.length()) {
                val f = filesJson.getJSONObject(j)
                val name = f.optString("name", "file")
                val mime = f.optString("mime", "application/octet-stream")
                val attachmentId = f.optString("id", null)?.takeIf { it.isNotBlank() }
                val data = f.optString("data", null)?.takeIf { it.isNotBlank() }
                val resolved = resolveFile(name, mime, attachmentId, data, pin)
                if (resolved.attachmentId != attachmentId) {
                    migrated = true
                }
                files.add(resolved)
            }
            val linksJson = obj.optJSONArray("linkPreviews") ?: JSONArray()
            val linkPreviews = mutableListOf<NoteLinkPreview>()
            for (j in 0 until linksJson.length()) {
                val l = linksJson.getJSONObject(j)
                linkPreviews.add(
                    NoteLinkPreview(
                        url = l.getString("url"),
                        title = l.optString("title", null),
                        description = l.optString("description", null),
                        imageUrl = l.optString("imageUrl", null),
                        cachedImagePath = l.optString("cachedImagePath", null)
                            ?.takeIf { it.isNotBlank() }
                    )
                )
            }
            val eventObj = obj.optJSONObject("event")
            val event = eventObj?.let {
                val alarmMinutes = when {
                    it.has("alarmMinutesBeforeStart") && !it.isNull("alarmMinutesBeforeStart") ->
                        it.optInt("alarmMinutesBeforeStart")
                    it.has("reminderMinutesBeforeStart") && !it.isNull("reminderMinutesBeforeStart") ->
                        it.optInt("reminderMinutesBeforeStart")
                    else -> null
                }
                val notificationMinutes = if (
                    it.has("notificationMinutesBeforeStart") &&
                        !it.isNull("notificationMinutesBeforeStart")
                ) {
                    it.optInt("notificationMinutesBeforeStart")
                } else {
                    null
                }
                NoteEvent(
                    start = it.getLong("start"),
                    end = it.getLong("end"),
                    allDay = it.optBoolean("allDay", false),
                    timeZone = it.optString("timeZone", java.util.TimeZone.getDefault().id),
                    location = it.optString("location", null)
                        ?.takeIf { location -> location.isNotBlank() },
                    alarmMinutesBeforeStart = alarmMinutes,
                    notificationMinutesBeforeStart = notificationMinutes,
                )
            }
            notes.add(
                Note(
                    id = obj.getLong("id"),
                    title = obj.getString("title"),
                    content = obj.getString("content"),
                    date = obj.getLong("date"),
                    images = images,
                    files = files,
                    linkPreviews = linkPreviews,
                    summary = obj.optString("summary", ""),
                    event = event,
                    isLocked = obj.optBoolean("locked", false),
                )
            )
        }
        if (migrated) {
            saveNotes(notes, pin)
        }
        return notes
    }

    fun saveNotes(notes: List<Note>, pin: String) {
        val arr = JSONArray()
        notes.forEach { note ->
            val obj = JSONObject()
            obj.put("id", note.id)
            obj.put("title", note.title)
            obj.put("content", note.content)
            obj.put("date", note.date)
            val imagesArray = JSONArray()
            note.images.forEach { image ->
                val id = image.attachmentId ?: image.data?.let { data ->
                    decodeBase64(data)?.let { bytes ->
                        runCatching { attachmentStore.saveAttachment(pin, bytes) }.getOrNull()
                    }
                }
                if (id != null) {
                    imagesArray.put(JSONObject().apply { put("id", id) })
                } else if (!image.data.isNullOrBlank()) {
                    imagesArray.put(JSONObject().apply { put("data", image.data) })
                }
            }
            obj.put("images", imagesArray)
            val filesArray = JSONArray()
            note.files.forEach { f ->
                val fo = JSONObject()
                fo.put("name", f.name)
                fo.put("mime", f.mime)
                val id = f.attachmentId ?: f.data?.let { data ->
                    decodeBase64(data)?.let { bytes ->
                        runCatching { attachmentStore.saveAttachment(pin, bytes) }.getOrNull()
                    }
                }
                if (id != null) {
                    fo.put("id", id)
                } else if (!f.data.isNullOrBlank()) {
                    fo.put("data", f.data)
                }
                filesArray.put(fo)
            }
            obj.put("files", filesArray)
            val linksArray = JSONArray()
            note.linkPreviews.forEach { link ->
                val lo = JSONObject()
                lo.put("url", link.url)
                link.title?.let { lo.put("title", it) }
                link.description?.let { lo.put("description", it) }
                link.imageUrl?.let { lo.put("imageUrl", it) }
                link.cachedImagePath?.let { lo.put("cachedImagePath", it) }
                linksArray.put(lo)
            }
            obj.put("linkPreviews", linksArray)
            obj.put("summary", note.summary)
            note.event?.let { event ->
                val eo = JSONObject()
                eo.put("start", event.start)
                eo.put("end", event.end)
                eo.put("allDay", event.allDay)
                eo.put("timeZone", event.timeZone)
                event.location?.let { eo.put("location", it) }
                event.alarmMinutesBeforeStart?.let {
                    eo.put("alarmMinutesBeforeStart", it)
                    // Legacy field for backward compatibility with previous builds.
                    eo.put("reminderMinutesBeforeStart", it)
                }
                event.notificationMinutesBeforeStart?.let {
                    eo.put("notificationMinutesBeforeStart", it)
                }
                obj.put("event", eo)
            }
            obj.put("locked", note.isLocked)
            arr.put(obj)
        }
        val root = JSONObject().apply {
            put("version", VERSION)
            put("notes", arr)
        }
        val json = root.toString().toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = deriveKey(pin, salt)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(json)
        val output = ByteArray(16 + 12 + cipherText.size)
        System.arraycopy(salt, 0, output, 0, 16)
        System.arraycopy(iv, 0, output, 16, 12)
        System.arraycopy(cipherText, 0, output, 28, cipherText.size)
        file.writeBytes(output)
    }

    private fun resolveImage(
        attachmentId: String?,
        base64Data: String?,
        pin: String,
    ): NoteImage {
        if (!attachmentId.isNullOrBlank()) {
            return NoteImage(attachmentId = attachmentId)
        }
        val decoded = base64Data?.let(::decodeBase64) ?: return NoteImage(data = base64Data)
        val id = runCatching { attachmentStore.saveAttachment(pin, decoded) }.getOrNull()
        return if (id != null) {
            NoteImage(attachmentId = id)
        } else {
            NoteImage(data = base64Data)
        }
    }

    private fun resolveFile(
        name: String,
        mime: String,
        attachmentId: String?,
        base64Data: String?,
        pin: String,
    ): NoteFile {
        if (!attachmentId.isNullOrBlank()) {
            return NoteFile(name = name, mime = mime, attachmentId = attachmentId)
        }
        val decoded = base64Data?.let(::decodeBase64)
        if (decoded != null) {
            val id = runCatching { attachmentStore.saveAttachment(pin, decoded) }.getOrNull()
            if (id != null) {
                return NoteFile(name = name, mime = mime, attachmentId = id)
            }
        }
        return NoteFile(name = name, mime = mime, data = base64Data)
    }

    private fun decodeBase64(data: String): ByteArray? {
        return runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
    }

    private fun deriveKey(pin: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 10000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }
}

