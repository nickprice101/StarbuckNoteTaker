package com.example.starbucknotetaker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device summariser powered by a lightweight TensorFlow Lite classifier.
 *
 * The interpreter predicts a coarse note category which is then converted into
 * an "enhanced summary" that mirrors the format produced by the training
 * pipeline (see app/src/main/assets/scripts/complete_pipeline.py).
 */
class Summarizer(
    private val context: Context,
    private val interpreterFactory: (MappedByteBuffer) -> LiteInterpreter = { TfLiteInterpreter.create(it) },
    private val logger: (String, Throwable) -> Unit = { msg, t -> Log.e("Summarizer", "summarizer: $msg", t) },
    private val debugSink: (String) -> Unit = { msg -> Log.d("Summarizer", "summarizer: $msg") },
    private val assetLoader: (Context, String) -> InputStream = { ctx, name -> ctx.assets.open(name) },
) {
    sealed class SummarizerState {
        object Loading : SummarizerState()
        object Ready : SummarizerState()
        object Fallback : SummarizerState()
        data class Error(val message: String) : SummarizerState()
    }

    private val _state = MutableStateFlow<SummarizerState>(SummarizerState.Ready)
    val state: StateFlow<SummarizerState> = _state

    private val debugTrace = ThreadLocal<MutableList<String>?>()
    private val lastDebugTrace = AtomicReference<List<String>>(emptyList())

    private var interpreter: LiteInterpreter? = null
    private var categories: List<String> = emptyList()

    private fun emitDebug(message: String) {
        debugTrace.get()?.add(message)
        debugSink(message)
    }

    suspend fun warmUp(): SummarizerState = withContext(Dispatchers.Default) {
        try {
            loadModelIfNeeded()
        } catch (t: Throwable) {
            logger("warm up failed", t)
        }
        state.value
    }

    fun consumeDebugTrace(): List<String> {
        return lastDebugTrace.getAndSet(emptyList())
    }

    suspend fun summarize(text: String): String = withContext(Dispatchers.Default) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            lastDebugTrace.set(emptyList())
            return@withContext ""
        }

        val trace = mutableListOf<String>()
        debugTrace.set(trace)

        try {
            emitDebug("summarizing text of length ${trimmed.length}")
            loadModelIfNeeded()
            val interpreter = interpreter
            val categories = categories
            if (interpreter == null || categories.isEmpty()) {
                return@withContext fallbackFromSummarize("model unavailable", trimmed)
            }

            val output = Array(1) { FloatArray(categories.size) }
            val input = arrayOf(trimmed)
            interpreter.run(input, output)
            val scores = output[0]
            val predictedIndex = scores.indices.maxByOrNull { scores[it] } ?: 0
            val predictedCategory = categories.getOrNull(predictedIndex) ?: categories.first()
            emitDebug(
                "predicted category=$predictedCategory score=${String.format(Locale.US, "%.3f", scores[predictedIndex])}"
            )

            val summary = generateEnhancedSummary(trimmed, predictedCategory)
            emitDebug("generated enhanced summary: $summary")
            _state.emit(SummarizerState.Ready)
            summary
        } catch (t: Throwable) {
            logger("summarizer inference failed", t)
            fallbackFromSummarize(t.message ?: "inference failure", trimmed)
        } finally {
            lastDebugTrace.set(debugTrace.get()?.toList() ?: emptyList())
            debugTrace.set(null)
        }
    }

    private suspend fun fallbackFromSummarize(reason: String, text: String): String {
        emitDebug("falling back due to $reason")
        emitDebug("fallback reason: $reason")
        _state.emit(SummarizerState.Fallback)
        return fallbackSummaryInternal(text)
    }

    suspend fun fallbackSummary(text: String): String = fallbackSummary(text, null)

    suspend fun fallbackSummary(text: String, @Suppress("UNUSED_PARAMETER") event: NoteEvent?): String =
        withContext(Dispatchers.Default) { fallbackSummaryInternal(text) }

    fun quickFallbackSummary(text: String): String {
        return smartTruncate(lightweightPreview(text), MAX_SUMMARY_LENGTH)
    }

    private fun fallbackSummaryInternal(text: String): String {
        val contentOnly = extractContentForFallback(text)
        val normalized = contentOnly.trim().replace(WHITESPACE_REGEX, " ")
        if (normalized.isEmpty()) return ""

        val truncated = normalized.take(FALLBACK_CHAR_LIMIT)
        return formatFallbackAcrossTwoLines(truncated)
    }

    private fun extractContentForFallback(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""

        val separatorIndex = trimmed.indexOf("\n\n")
        if (separatorIndex >= 0 && separatorIndex + 2 < trimmed.length) {
            val afterSeparator = trimmed.substring(separatorIndex + 2).trim()
            if (afterSeparator.isNotEmpty()) {
                return afterSeparator
            }
        }

        if (trimmed.startsWith("Title:", ignoreCase = true)) {
            return trimmed.removePrefix("Title:").trim()
        }

        return trimmed
    }

    private fun formatFallbackAcrossTwoLines(truncated: String): String {
        if (truncated.isEmpty()) return ""
        if (truncated.length <= FALLBACK_FIRST_LINE_TARGET) {
            return truncated
        }

        val desiredBreak = minOf(truncated.length, FALLBACK_FIRST_LINE_TARGET)
        val whitespaceBreak = truncated.lastIndexOf(' ', desiredBreak)
        val breakIndex = when {
            whitespaceBreak in 0 until truncated.length - 1 -> whitespaceBreak + 1
            truncated.length > FALLBACK_FIRST_LINE_TARGET -> FALLBACK_FIRST_LINE_TARGET
            else -> truncated.length
        }

        if (breakIndex <= 0 || breakIndex >= truncated.length) {
            return truncated
        }

        return truncated.substring(0, breakIndex) + "\n" + truncated.substring(breakIndex)
    }

    private suspend fun loadModelIfNeeded() {
        if (interpreter != null && categories.isNotEmpty()) return
        emitDebug("loading summarizer assets")
        _state.emit(SummarizerState.Loading)
        try {
            val modelsDir = File(context.filesDir, MODELS_DIR_NAME).apply { mkdirs() }
            val modelFile = ensureAsset(modelsDir, MODEL_ASSET_NAME)
            val mappingFile = ensureAsset(modelsDir, CATEGORY_MAPPING_ASSET_NAME)
            categories = parseCategoryMapping(mappingFile)
            interpreter = interpreterFactory(mapFile(modelFile))
            emitDebug("summarizer model ready with ${categories.size} categories")
            _state.emit(SummarizerState.Ready)
        } catch (t: Throwable) {
            logger("failed to load summarizer assets", t)
            _state.emit(SummarizerState.Error(t.message ?: "Failed to load summarizer assets"))
            throw t
        }
    }

    private fun parseCategoryMapping(file: File): List<String> {
        val text = file.readText()
        val json = JSONObject(text)
        val array = json.optJSONArray("categories") ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
            result += array.optString(i)
        }
        return result
    }

    private fun ensureAsset(modelsDir: File, assetName: String): File {
        val target = File(modelsDir, assetName)
        if (target.exists() && target.length() > 0) return target
        assetLoader(context, assetName).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        return target
    }

    private fun mapFile(file: File): MappedByteBuffer {
        RandomAccessFile(file, "r").use { raf ->
            return raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }
    }

    private fun generateEnhancedSummary(noteText: String, rawCategory: String): String {
        val category = rawCategory.uppercase(Locale.US)
        val categoryFriendly = rawCategory.split('_')
            .joinToString(" ") { part ->
                part.lowercase(Locale.US).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
            }

        val parts = noteText.split(':', limit = 2)
        val contextPart = parts.getOrNull(0)?.trim().orEmpty().takeIf { parts.size > 1 } ?: ""
        val contentPart = if (parts.size > 1) parts[1].trim() else noteText.trim()
        val contentLower = contentPart.lowercase(Locale.US)

        val isList = contentPart.count { it == ',' } >= 2 || contentLower.split(" and ").size - 1 >= 2
        val wordCount = contentPart.split(WHITESPACE_REGEX).count { it.isNotBlank() }
        val isLong = wordCount > 50
        val subjects = SUBJECT_REGEX.findAll(noteText).map { it.value.trim() }.toList()

        val summary = when (category) {
            "SHOPPING_LIST" -> buildShoppingListSummary(contentPart)
            "GENERAL_CHECKLIST" -> buildGeneralChecklistSummary(contextPart, contentPart)
            "FOOD_RECIPE" -> buildFoodRecipeSummary(contextPart, isLong)
            "MEETING_RECAP" -> buildMeetingRecapSummary(contextPart, contentLower)
            "TRAVEL_LOG" -> buildTravelLogSummary(contextPart, subjects)
            "WORK_PROJECT" -> buildWorkProjectSummary(contextPart, contentLower)
            "TECHNICAL_REFERENCE" -> buildTechnicalReferenceSummary(contextPart, contentLower)
            "CREATIVE_WRITING" -> buildCreativeWritingSummary(contextPart, noteText)
            "FINANCE_LEGAL" -> buildFinanceSummary(contextPart, contentPart)
            "HEALTH_WELLNESS" -> buildHealthSummary(contextPart, contentLower)
            "EDUCATION_LEARNING" -> buildEducationSummary(contextPart, contentLower)
            "REMINDER" -> buildReminderSummary(contentPart)
            "PERSONAL_DAILY_LIFE" -> buildPersonalDailyLifeSummary(contextPart)
            "HOME_FAMILY" -> buildHomeFamilySummary(contextPart, isList)
            "SELF_IMPROVEMENT" -> buildSelfImprovementSummary(contextPart)
            else -> buildGenericSummary(categoryFriendly, contextPart, contentPart)
        }
        return smartTruncate(summary, MAX_SUMMARY_LENGTH)
    }

    private fun buildShoppingListSummary(contentPart: String): String {
        val items = SHOPPING_SPLIT_REGEX.split(contentPart).map { it.trim().trim('.') }.filter { it.isNotEmpty() }
        val cleanItems = items.take(4).mapNotNull { item ->
            val filtered = item.split(WHITESPACE_REGEX)
                .filter { token ->
                    token.isNotEmpty() && token.none { it.isDigit() } && token.lowercase(Locale.US) !in SHOPPING_STOP_WORDS
                }
            val product = filtered.joinToString(" ").take(30).trim()
            product.ifBlank { null }
        }
        val count = items.size
        return if (count <= 3 && cleanItems.isNotEmpty()) {
            "Shopping list for ${cleanItems.joinToString(", ")}"
        } else if (cleanItems.size >= 2) {
            "Shopping list with $count items including ${cleanItems.take(2).joinToString(" and ")} and more"
        } else {
            "Shopping list with $count items"
        }
    }

    private fun buildGeneralChecklistSummary(contextPart: String, contentPart: String): String {
        val rawItems = CHECKLIST_SPLIT_REGEX.split(contentPart)
        val cleanedItems = rawItems.mapNotNull { raw ->
            val trimmed = CHECKLIST_BULLET_CLEANER.replace(raw.trim(), "").trim().trimEnd('.')
            trimmed.takeIf { it.isNotEmpty() }
        }

        val label = if (contextPart.isNotBlank()) {
            val lowered = contextPart.lowercase(Locale.US)
            val stripped = CHECKLIST_LABEL_STRIP_REGEX.replace(lowered, "").trim()
            if (stripped.isNotBlank()) stripped else "key tasks"
        } else {
            "key tasks"
        }

        return when {
            cleanedItems.size >= 3 -> "Checklist for $label with ${cleanedItems.size} tasks including ${cleanedItems[0]} and ${cleanedItems[1]}"
            cleanedItems.size == 2 -> "Checklist for $label covering ${cleanedItems[0]} and ${cleanedItems[1]}"
            cleanedItems.size == 1 -> "Checklist for $label highlighting ${cleanedItems[0]}"
            contextPart.isNotBlank() -> "Checklist outlining $label"
            else -> "Task checklist with multiple items"
        }
    }

    private fun buildFoodRecipeSummary(contextPart: String, isLong: Boolean): String {
        return if (contextPart.isNotBlank()) {
            val recipeName = contextPart.lowercase(Locale.US)
                .replace(" recipe", "")
                .replace(" how to make", "")
                .trim()
            if (isLong) {
                "Recipe with detailed instructions for preparing $recipeName"
            } else {
                "Recipe for $recipeName"
            }
        } else {
            "Recipe note with cooking instructions"
        }
    }

    private fun buildMeetingRecapSummary(contextPart: String, contentLower: String): String {
        val meetingType = contextPart.takeIf { it.isNotBlank() }?.lowercase(Locale.US) ?: "team meeting"
        val keyTopics = mutableListOf<String>()
        if ("assigned" in contentLower || "action" in contentLower) {
            keyTopics += "task assignments"
        }
        if ("discussed" in contentLower || "aligned" in contentLower) {
            keyTopics += "key decisions"
        }
        if ("scheduled" in contentLower) {
            keyTopics += "scheduling"
        }
        return if (keyTopics.isNotEmpty()) {
            "Meeting note on $meetingType covering ${keyTopics.take(2).joinToString(" and ")}"
        } else {
            "Meeting recap documenting $meetingType discussions"
        }
    }

    private fun buildTravelLogSummary(contextPart: String, subjects: List<String>): String {
        val destination = subjects.firstOrNull { it !in TRAVEL_SUBJECT_EXCLUSIONS }
        return when {
            destination != null -> "Travel log documenting journey to $destination with experiences and observations"
            contextPart.isNotBlank() -> "Travel experience note about ${contextPart.lowercase(Locale.US)}"
            else -> "Travel log entry capturing experiences and insights"
        }
    }

    private fun buildWorkProjectSummary(contextPart: String, contentLower: String): String {
        val projectName = contextPart.ifBlank { "project work" }
        return when {
            "milestone" in contentLower || "deliverable" in contentLower ->
                "Work project note for $projectName outlining milestones and deliverables"
            "status" in contentLower || "update" in contentLower ->
                "Project status update on $projectName"
            else -> "Work project tracking progress on $projectName"
        }
    }

    private fun buildTechnicalReferenceSummary(contextPart: String, contentLower: String): String {
        val procedure = contextPart.takeIf { it.isNotBlank() }?.lowercase(Locale.US) ?: "system procedure"
        return if ("command" in contentLower || "code" in contentLower) {
            "Technical reference documenting command syntax for $procedure"
        } else {
            "Technical documentation with step-by-step procedures for $procedure"
        }
    }

    private fun buildCreativeWritingSummary(contextPart: String, noteText: String): String {
        val theme = contextPart.takeIf { it.isNotBlank() }?.lowercase(Locale.US) ?: "creative piece"
        val lower = noteText.lowercase(Locale.US)
        return when {
            "poetry" in lower || "poetic" in lower -> "Creative writing: poetic composition about $theme"
            "story" in lower -> "Creative writing: story development exploring $theme"
            else -> "Creative writing piece: $theme"
        }
    }

    private fun buildFinanceSummary(contextPart: String, contentPart: String): String {
        val activity = contextPart.takeIf { it.isNotBlank() }?.lowercase(Locale.US) ?: "financial activity"
        val match = CURRENCY_REGEX.find(contentPart)
        return if (match != null) {
            "Financial record for $activity involving ${match.value}"
        } else {
            "Financial note documenting $activity"
        }
    }

    private fun buildHealthSummary(contextPart: String, contentLower: String): String {
        val activity = contextPart.takeIf { it.isNotBlank() }?.lowercase(Locale.US) ?: "wellness activity"
        val metric = HEALTH_METRIC_REGEX.find(contentLower)?.value
        return if (metric != null) {
            "Health tracking note for $activity recording $metric"
        } else {
            "Health and wellness note about $activity"
        }
    }

    private fun buildEducationSummary(contextPart: String, contentLower: String): String {
        val subject = contextPart.takeIf { it.isNotBlank() }?.lowercase(Locale.US) ?: "learning activity"
        return if ("completed" in contentLower || "passed" in contentLower) {
            "Learning milestone: completed $subject"
        } else {
            "Educational note on $subject and ongoing development"
        }
    }

    private fun buildReminderSummary(contentPart: String): String {
        var action = contentPart.trim()
        val lower = action.lowercase(Locale.US)
        if (lower.startsWith("to ")) {
            action = action.drop(3)
        }
        if (action.length > 100) {
            val truncated = action.substring(0, 100)
            var breakPoint: Int? = null
            for (marker in REMINDER_BREAKS) {
                val idx = truncated.lastIndexOf(marker)
                if (idx >= 0) {
                    breakPoint = idx
                    break
                }
            }
            action = if (breakPoint != null) {
                truncated.substring(0, breakPoint)
            } else {
                val lastSpace = truncated.lastIndexOf(' ')
                if (lastSpace > 0) truncated.substring(0, lastSpace) else truncated
            }
        }
        action = action.trim().trimEnd(',', ';')
        return "Reminder to $action"
    }

    private fun buildPersonalDailyLifeSummary(contextPart: String): String {
        val activity = contextPart.takeIf { it.isNotBlank() }?.lowercase(Locale.US) ?: "daily activity"
        return "Daily life note about $activity"
    }

    private fun buildHomeFamilySummary(contextPart: String, isList: Boolean): String {
        val topic = contextPart.takeIf { it.isNotBlank() }?.lowercase(Locale.US) ?: "family matter"
        return if (isList) {
            "Family coordination note regarding $topic with multiple items"
        } else {
            "Family and home note about $topic"
        }
    }

    private fun buildSelfImprovementSummary(contextPart: String): String {
        val focus = contextPart.takeIf { it.isNotBlank() }?.lowercase(Locale.US) ?: "personal development"
        return "Personal growth note on $focus for self-improvement"
    }

    private fun buildGenericSummary(categoryFriendly: String, contextPart: String, contentPart: String): String {
        return if (contextPart.isNotBlank()) {
            "$categoryFriendly note about ${contextPart.lowercase(Locale.US)}"
        } else {
            val words = contentPart.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
            val firstWords = words.take(10).joinToString(" ")
            "$categoryFriendly: $firstWords..."
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        categories = emptyList()
    }

    companion object {
        internal const val MODELS_DIR_NAME = "models"
        internal const val MODEL_ASSET_NAME = "note_classifier.tflite"
        internal const val CATEGORY_MAPPING_ASSET_NAME = "category_mapping.json"
        private const val MAX_SUMMARY_LENGTH = 140
        private const val MAX_PREVIEW_LENGTH = 160
        private const val FALLBACK_CHAR_LIMIT = 150
        private const val FALLBACK_FIRST_LINE_TARGET = FALLBACK_CHAR_LIMIT / 2
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val SHOPPING_SPLIT_REGEX = Regex(",| and ", RegexOption.IGNORE_CASE)
        private val CHECKLIST_SPLIT_REGEX = Regex(",|;|\\band\\b|\\n", RegexOption.IGNORE_CASE)
        private val CHECKLIST_BULLET_CLEANER = Regex("^[\\-•▪●■∎*]+\\s*")
        private val CHECKLIST_LABEL_STRIP_REGEX = Regex("\\b(checklist|list|tasks)\\b", RegexOption.IGNORE_CASE)
        private val SUBJECT_REGEX = Regex("\\b[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*\\b")
        private val CURRENCY_REGEX = Regex("\\$[\\d,]+(?:\\.\\d{2})?")
        private val HEALTH_METRIC_REGEX = Regex("\\d+(?:\\.\\d+)?\\s*(?:miles|minutes|hours|lbs|kg|calories)")
        private val REMINDER_BREAKS = listOf(",", " and ", "; ")
        private val SHOPPING_STOP_WORDS = setOf("need", "get", "buy", "pick", "for", "the", "a", "an")
        private val TRAVEL_SUBJECT_EXCLUSIONS = setOf("Woke", "Packed", "Noted", "Explored", "Visited")

        internal fun lightweightPreview(text: String): String {
            val normalized = text.trim().replace(WHITESPACE_REGEX, " ")
            if (normalized.isEmpty()) return ""
            val sentences = SENTENCE_CAPTURE.findAll(normalized).map { it.value.trim() }.toList()
            val preview = when {
                sentences.isEmpty() -> normalized
                sentences.size == 1 -> sentences.first()
                else -> sentences.take(2).joinToString(" ")
            }
            return smartTruncate(preview, MAX_PREVIEW_LENGTH)
        }

        internal fun smartTruncate(text: String, maxLength: Int = MAX_SUMMARY_LENGTH): String {
            val normalized = text.trim().replace(WHITESPACE_REGEX, " ")
            if (normalized.length <= maxLength) return normalized
            val truncated = normalized.substring(0, maxLength)
            val sentenceEnd = truncated.lastIndexOfAny(charArrayOf('.', '!', '?'))
            if (sentenceEnd >= (maxLength * 0.7).toInt()) {
                return truncated.substring(0, sentenceEnd + 1).trim()
            }
            val lastSpace = truncated.lastIndexOf(' ')
            if (lastSpace > 0) {
                return truncated.substring(0, lastSpace).trim()
            }
            return truncated.trim()
        }

        private val SENTENCE_CAPTURE = Regex("([^.!?]+[.!?])")
    }
}
