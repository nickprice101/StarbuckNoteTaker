package com.example.starbucknotetaker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    private val activeJobLock = Any()
    private val inferenceJobs = mutableMapOf<Int, Job>()
    private val activeModes = mutableMapOf<String, LlamaEngine.Mode>()
    private var phraseJob: Job? = null
    private var progressJob: Job? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var engine: LlamaEngine
    private lateinit var webLookup: AssistantWebLookup
    private lateinit var memoryStore: ConversationMemoryStore
    private var currentPhraseIndex = 0

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        engine = LlamaEngineProvider.acquire(applicationContext)
        webLookup = AssistantWebLookup(applicationContext)
        memoryStore = ConversationMemoryStore(applicationContext)
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
        val reformatInstruction = intent.getStringExtra(EXTRA_REFORMAT_INSTRUCTION)
        val persistConversationMemory =
            intent.getBooleanExtra(EXTRA_PERSIST_CONVERSATION_MEMORY, true)

        if (text.isBlank()) {
            broadcastResult(requestId, noteId, mode, "", isError = false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notification = buildNotification(PHRASES.first())
        // On Android 10+ (API 29) the 3-arg overload is available; on Android 14 (API 34,
        // targetSdk 34) it is *required* when the manifest declares a foregroundServiceType.
        // Calling the 2-arg form when a type is declared throws InvalidForegroundServiceTypeException.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        ensurePhraseCycler()
        ensureProgressCollector()

        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = when (mode) {
                    LlamaEngine.Mode.SUMMARISE -> {
                        broadcastProgress(requestId, "", "Preparing summary", mode)
                        engine.summarise(text, requestId)
                    }
                    LlamaEngine.Mode.REWRITE -> {
                        broadcastProgress(requestId, "", "ADK agent is correcting and formatting", mode)
                        reformatNote(text, reformatInstruction, requestId)
                    }
                    LlamaEngine.Mode.QUESTION ->
                        answerQuestion(
                            question = text,
                            noteContext = context,
                            noteId = noteId,
                            persistConversationMemory = persistConversationMemory,
                            requestId = requestId,
                        )
                }
                broadcastResult(requestId, noteId, mode, result, isError = false)
            } catch (e: CancellationException) {
                Log.i(TAG, "Inference cancelled for requestId=$requestId")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed for requestId=$requestId", e)
                val errorDetail = "[${e::class.simpleName}] ${e.message ?: "Inference failed"}"
                broadcastResult(requestId, noteId, mode, errorDetail, isError = true)
            } finally {
                val noActiveJobs = synchronized(activeJobLock) {
                    inferenceJobs.remove(startId)
                    activeModes.remove(requestId)
                    inferenceJobs.isEmpty()
                }
                if (noActiveJobs) {
                    stopProgressUpdates()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        synchronized(activeJobLock) {
            inferenceJobs[startId] = job
            activeModes[requestId] = mode
        }
        job.start()

        return START_NOT_STICKY
    }

    private suspend fun answerQuestion(
        question: String,
        noteContext: String?,
        noteId: Long,
        persistConversationMemory: Boolean,
        requestId: String,
    ): String {
        val memory = memoryStore.get(noteId, persistConversationMemory)
        broadcastProgress(requestId, "", "Planning local and online evidence", LlamaEngine.Mode.QUESTION)
        val plan = runCatching {
            engine.planResearch(
                question = question,
                taskId = "$requestId-plan",
            )
        }.getOrElse {
            Log.w(TAG, "Qwen research planning failed; using conservative routing", it)
            QwenResearchPlan.fallback(question)
        }

        var research: WebLookupResult? = null
        val needsWeb = plan.needsWeb || AssistantWebLookup.requiresInternet(question)
        if (needsWeb) {
            broadcastProgress(
                requestId,
                "",
                AssistantWebLookup.RESEARCH_PROGRESS_MESSAGE,
                LlamaEngine.Mode.QUESTION,
            )
            val queries = plan.queries.ifEmpty { listOf(question) }
            val lookups = queries.take(MAX_PLANNED_RESEARCH_QUERIES).map { query ->
                webLookup.lookup(query)
            }
            val lookup = mergeWebLookupResults(queries.joinToString(" | "), lookups)
            if (lookup.results.isNotEmpty()) {
                research = lookup
                broadcastProgress(
                    requestId = requestId,
                    partialText = "",
                    status = "Web research complete; composing a concise answer",
                    mode = LlamaEngine.Mode.QUESTION,
                )
            } else if (AssistantWebLookup.requiresInternet(question)) {
                return AssistantWebLookup.INTERNET_REQUIRED_MESSAGE
            }
        }

        broadcastProgress(requestId, "", "Preparing a direct answer", LlamaEngine.Mode.QUESTION)
        var finalAnswer = engine.answer(
            question = question,
            context = noteContext,
            taskId = requestId,
            webResearch = research?.toPromptContext(),
            conversationMemory = memory,
        )
        if (research == null && AssistantWebLookup.answerNeedsResearch(finalAnswer)) {
            broadcastProgress(
                requestId,
                "",
                AssistantWebLookup.RESEARCH_PROGRESS_MESSAGE,
                LlamaEngine.Mode.QUESTION,
            )
            val lookup = webLookup.lookup(plan.queries.firstOrNull() ?: question)
            if (lookup.results.isNotEmpty()) {
                research = lookup
                broadcastProgress(
                    requestId,
                    "",
                    "Web research complete; composing a concise answer",
                    LlamaEngine.Mode.QUESTION,
                )
                finalAnswer = engine.answer(
                    question = question,
                    context = noteContext,
                    taskId = requestId,
                    webResearch = lookup.toPromptContext(),
                    conversationMemory = memory,
                )
            } else {
                return AssistantWebLookup.INTERNET_REQUIRED_MESSAGE
            }
        }
        broadcastProgress(requestId, "", "Verifying answer against its evidence", LlamaEngine.Mode.QUESTION)
        finalAnswer = runCatching {
            engine.verifyAnswer(
                question = question,
                draft = finalAnswer,
                noteContext = noteContext,
                webResearch = research?.toPromptContext(),
                taskId = "$requestId-verify",
            )
        }.getOrElse {
            Log.w(TAG, "Qwen answer verification failed; retaining grounded draft", it)
            finalAnswer
        }
        finalAnswer = research?.let { AssistantWebLookup.appendMarkdownSources(finalAnswer, it) }
            ?: finalAnswer

        if (noteId >= 0L && finalAnswer.isNotBlank()) {
            val updatedMemory = runCatching {
                engine.updateConversationMemory(
                    previousMemory = memory,
                    question = question,
                    answer = finalAnswer,
                    taskId = "$requestId-memory",
                )
            }.getOrElse {
                Log.w(TAG, "Qwen conversation memory update failed", it)
                memory
            }
            memoryStore.put(noteId, updatedMemory, persistConversationMemory)
        }
        return finalAnswer
    }

    private suspend fun reformatNote(
        noteText: String,
        userInstruction: String?,
        requestId: String,
    ): String {
        val research = userInstruction
            ?.takeIf(ReformatResearchPolicy::requiresOnlineEvidence)
            ?.let { instruction ->
                broadcastProgress(
                    requestId,
                    "",
                    "Retrieving the requested public style or verification evidence",
                    LlamaEngine.Mode.REWRITE,
                )
                webLookup.lookup(instruction)
            }
            ?.takeIf { it.results.isNotEmpty() }
        return NoteAiAgent.reformat(
            context = applicationContext,
            noteText = noteText,
            taskId = requestId,
            userInstruction = userInstruction,
            webResearch = research?.toPromptContext(),
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdates()
        synchronized(activeJobLock) {
            inferenceJobs.values.forEach { it.cancel() }
            inferenceJobs.clear()
            activeModes.clear()
        }
        serviceScope.cancel()
        LlamaEngineProvider.releaseAfterIdle()
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

    private fun ensurePhraseCycler() {
        if (phraseJob?.isActive == true) return
        phraseJob = serviceScope.launch {
            while (true) {
                delay(PHRASE_CYCLE_MS)
                currentPhraseIndex = (currentPhraseIndex + 1) % PHRASES.size
                updateNotification(PHRASES[currentPhraseIndex])
            }
        }
    }

    private fun ensureProgressCollector() {
        if (progressJob?.isActive == true) return
        progressJob = serviceScope.launch {
            val lastBroadcastAt = mutableMapOf<String, Long>()
            val lastBroadcastText = mutableMapOf<String, String>()
            engine.progress.collect { progress ->
                when (progress) {
                    is LlamaEngine.InferenceProgress.Thinking -> {
                        val mode = synchronized(activeJobLock) {
                            activeModes[progress.taskId] ?: LlamaEngine.Mode.QUESTION
                        }
                        val now = SystemClock.elapsedRealtime()
                        val lastAt = lastBroadcastAt[progress.taskId] ?: 0L
                        val lastText = lastBroadcastText[progress.taskId].orEmpty()
                        val shouldBroadcast = lastText.isBlank() ||
                            now - lastAt >= PROGRESS_BROADCAST_MS ||
                            progress.partialText.length - lastText.length >= PROGRESS_MIN_CHAR_DELTA
                        if (shouldBroadcast) {
                            lastBroadcastAt[progress.taskId] = now
                            lastBroadcastText[progress.taskId] = progress.partialText
                            broadcastProgress(
                                requestId = progress.taskId,
                                partialText = progress.partialText,
                                status = progressStatusFor(mode),
                                mode = mode,
                            )
                        }
                    }
                    is LlamaEngine.InferenceProgress.Throttled -> {
                        updateNotification(THROTTLE_MESSAGE)
                    }
                    is LlamaEngine.InferenceProgress.Error -> {
                        val mode = synchronized(activeJobLock) {
                            activeModes[progress.taskId] ?: LlamaEngine.Mode.QUESTION
                        }
                        broadcastProgress(
                            requestId = progress.taskId,
                            partialText = "",
                            status = progress.message,
                            mode = mode,
                        )
                    }
                    is LlamaEngine.InferenceProgress.Done,
                    LlamaEngine.InferenceProgress.Idle -> Unit
                }
            }
        }
    }

    private fun stopProgressUpdates() {
        phraseJob?.cancel()
        phraseJob = null
        progressJob?.cancel()
        progressJob = null
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

    private fun broadcastProgress(
        requestId: String,
        partialText: String,
        status: String,
        mode: LlamaEngine.Mode = LlamaEngine.Mode.QUESTION,
    ) {
        val broadcastIntent = Intent(ACTION_PROGRESS).apply {
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_MODE, mode.name)
            putExtra(EXTRA_PROGRESS_TEXT, partialText)
            putExtra(EXTRA_PROGRESS_STATUS, status)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(broadcastIntent)
    }

    private fun progressStatusFor(mode: LlamaEngine.Mode): String =
        when (mode) {
            LlamaEngine.Mode.SUMMARISE -> "Generating summary"
            LlamaEngine.Mode.REWRITE -> "Refining formatted note"
            LlamaEngine.Mode.QUESTION -> "Generating answer"
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
        const val EXTRA_PROGRESS_TEXT = "llama_progress_text"
        const val EXTRA_PROGRESS_STATUS = "llama_progress_status"
        const val EXTRA_REFORMAT_INSTRUCTION = "llama_reformat_instruction"
        const val EXTRA_PERSIST_CONVERSATION_MEMORY = "llama_persist_conversation_memory"

        /** Broadcast action sent when inference is complete. */
        const val ACTION_RESULT = "com.example.starbucknotetaker.LLAMA_RESULT"
        const val ACTION_PROGRESS = "com.example.starbucknotetaker.LLAMA_PROGRESS"

        private const val PHRASE_CYCLE_MS = 2_500L
        private const val PROGRESS_BROADCAST_MS = 750L
        private const val PROGRESS_MIN_CHAR_DELTA = 24
        private const val MAX_PLANNED_RESEARCH_QUERIES = 2

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
            reformatInstruction: String? = null,
            persistConversationMemory: Boolean = true,
        ): Intent = Intent(context, LlamaForegroundService::class.java).apply {
            putExtra(EXTRA_MODE, mode.name)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_NOTE_ID, noteId)
            putExtra(EXTRA_REQUEST_ID, requestId)
            if (contextText != null) putExtra(EXTRA_CONTEXT_TEXT, contextText)
            if (reformatInstruction != null) {
                putExtra(EXTRA_REFORMAT_INSTRUCTION, reformatInstruction)
            }
            putExtra(EXTRA_PERSIST_CONVERSATION_MEMORY, persistConversationMemory)
        }
    }
}

internal object ReformatResearchPolicy {
    private val explicitOnlineMode = Regex(
        """\b(?:verify|fact[- ]?check|check citations?|validate sources?|style guide|APA|MLA|Chicago|linked (?:page|source|material)|online source|web source)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun requiresOnlineEvidence(instruction: String): Boolean =
        explicitOnlineMode.containsMatchIn(instruction)
}
