package com.example.starbucknotetaker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that wraps heavy on-device LLM inference so Android does
 * not kill the process during extended computation.
 *
 * Clients start the service via [buildIntent] and listen for results by
 * registering a [androidx.localbroadcastmanager.content.LocalBroadcastManager]
 * receiver for [ACTION_RESULT].
 *
 * The service posts an ongoing notification with witty rotating status phrases
 * while inference is running, and optionally reflects thermal-throttle events.
 */
class LlamaForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var inferenceJob: Job? = null
    private var phraseJob: Job? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var engine: LlamaEngine
    private var currentPhraseIndex = 0

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        engine = LlamaEngine(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val mode = intent.getStringExtra(EXTRA_MODE)?.let {
            runCatching { LlamaEngine.Mode.valueOf(it) }.getOrNull()
        } ?: LlamaEngine.Mode.SUMMARISE

        val text      = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val context   = intent.getStringExtra(EXTRA_CONTEXT_TEXT)
        val noteId    = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: text.hashCode().toString()

        if (text.isBlank()) {
            broadcastResult(requestId, noteId, mode, "", isError = false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notification = buildNotification(PHRASES.first())
        startForeground(NOTIFICATION_ID, notification)
        startPhraseCycler()

        inferenceJob?.cancel()
        inferenceJob = serviceScope.launch {
            try {
                val result = when (mode) {
                    LlamaEngine.Mode.SUMMARISE -> engine.summarise(text, requestId)
                    LlamaEngine.Mode.REWRITE   -> engine.rewrite(text, requestId)
                    LlamaEngine.Mode.QUESTION  -> engine.answer(text, context, requestId)
                }
                broadcastResult(requestId, noteId, mode, result, isError = false)
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed for requestId=$requestId", e)
                broadcastResult(requestId, noteId, mode, e.message ?: "Inference failed", isError = true)
            } finally {
                phraseJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }

        // Observe thermal throttle events and update the notification
        serviceScope.launch {
            engine.progress.collect { progress ->
                if (progress is LlamaEngine.InferenceProgress.Throttled) {
                    updateNotification(THROTTLE_MESSAGE)
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        phraseJob?.cancel()
        inferenceJob?.cancel()
        serviceScope.cancel()
        engine.close()
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Inference",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while the on-device AI is generating text"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(phrase: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notepad)
            .setContentTitle("✨ AI is thinking…")
            .setContentText(phrase)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun updateNotification(phrase: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(phrase))
    }

    private fun startPhraseCycler() {
        phraseJob?.cancel()
        phraseJob = serviceScope.launch {
            while (true) {
                delay(PHRASE_CYCLE_MS)
                currentPhraseIndex = (currentPhraseIndex + 1) % PHRASES.size
                updateNotification(PHRASES[currentPhraseIndex])
            }
        }
    }

    // ------------------------------------------------------------------
    // Broadcast helpers
    // ------------------------------------------------------------------

    private fun broadcastResult(
        requestId: String,
        noteId: Long,
        mode: LlamaEngine.Mode,
        result: String,
        isError: Boolean,
    ) {
        val broadcastIntent = Intent(ACTION_RESULT).apply {
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_NOTE_ID, noteId)
            putExtra(EXTRA_MODE, mode.name)
            putExtra(EXTRA_RESULT, result)
            putExtra(EXTRA_IS_ERROR, isError)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(broadcastIntent)
        Log.d(TAG, "Result broadcast for requestId=$requestId mode=$mode error=$isError")
    }

    companion object {
        private const val TAG = "LlamaForegroundService"
        private const val CHANNEL_ID = "llama_inference"
        private const val NOTIFICATION_ID = 0x4C4C41  // "LLA" in hex

        // ------------------------------------------------------------------
        // Intent extras
        // ------------------------------------------------------------------
        const val EXTRA_MODE         = "llama_mode"
        const val EXTRA_TEXT         = "llama_text"
        const val EXTRA_CONTEXT_TEXT = "llama_context_text"
        const val EXTRA_NOTE_ID      = "llama_note_id"
        const val EXTRA_REQUEST_ID   = "llama_request_id"
        const val EXTRA_RESULT       = "llama_result"
        const val EXTRA_IS_ERROR     = "llama_is_error"

        /** Broadcast action sent when inference is complete. */
        const val ACTION_RESULT = "com.example.starbucknotetaker.LLAMA_RESULT"

        private const val PHRASE_CYCLE_MS = 2_500L

        private const val THROTTLE_MESSAGE = "Taking a breather 🌡️ — cooling down…"

        private val PHRASES = listOf(
            "Brewing some AI magic ✨",
            "Connecting neurons… 🧠",
            "Assembling your thoughts 📝",
            "Channelling the oracle… 🔮",
            "Making it snappy 🚀",
            "Consulting the digital muse 🎨",
            "Polishing your prose ✍️",
            "Deep in thought… 💭",
            "Crunching the good stuff 🔬",
            "Almost there, bear with me 🐻",
        )

        /**
         * Builds a start intent for the service.
         *
         * @param context     Caller context.
         * @param mode        Inference mode.
         * @param text        Primary input text (note content / question).
         * @param noteId      ID of the note to update with the result, or -1.
         * @param requestId   Unique request identifier echoed in the result broadcast.
         * @param contextText Optional grounding context for [LlamaEngine.Mode.QUESTION].
         */
        fun buildIntent(
            context: Context,
            mode: LlamaEngine.Mode,
            text: String,
            noteId: Long = -1L,
            requestId: String = java.util.UUID.randomUUID().toString(),
            contextText: String? = null,
        ): Intent = Intent(context, LlamaForegroundService::class.java).apply {
            putExtra(EXTRA_MODE, mode.name)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_NOTE_ID, noteId)
            putExtra(EXTRA_REQUEST_ID, requestId)
            if (contextText != null) putExtra(EXTRA_CONTEXT_TEXT, contextText)
        }
    }
}
