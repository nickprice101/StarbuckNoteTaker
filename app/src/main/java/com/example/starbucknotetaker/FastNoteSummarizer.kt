package com.example.starbucknotetaker

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max

/**
 * Fast, fully offline note summarizer for note previews.
 *
 * The bundled TFLite model is used only as a category signal. The prose summary
 * is generated from the note contents so previews stay specific, less
 * repetitive, and bounded to a few hundred milliseconds on normal devices.
 */
internal class FastNoteSummarizer(
    private val context: Context,
    private val assetLoader: (Context, String) -> InputStream,
    private val logger: (String, Throwable) -> Unit = { msg, t -> Log.w(TAG, msg, t) },
) {
    private val appContext = context.applicationContext
    private val interpreterLock = Any()

    @Volatile
    private var interpreterLoadAttempted = false

    @Volatile
    private var interpreter: Interpreter? = null

    private val categories: List<String> by lazy { loadCategories() }
    private val vocabulary: TokenizerVocabulary by lazy { loadVocabulary() }

    fun warmUp(): Boolean {
        runCatching {
            categories
            vocabulary
            interpreterOrNull()
        }.onFailure { logger("fast summarizer warm-up failed", it) }
        return true
    }

    fun summarize(text: String): String {
        val parts = NoteParts.parse(text)
        if (parts.body.isBlank() && parts.title.isBlank()) return ""
        val category = classify(parts)
        return SummaryComposer.compose(parts, category.category)
    }

    fun close() {
        synchronized(interpreterLock) {
            interpreter?.close()
            interpreter = null
            interpreterLoadAttempted = false
        }
    }

    private fun classify(parts: NoteParts): CategoryScore {
        val heuristic = HeuristicCategoryClassifier.classify(parts)
        val model = classifyWithTflite(parts.fullText)
        return when {
            model == null -> heuristic
            model.confidence >= TFLITE_HIGH_CONFIDENCE && heuristic.confidence < HEURISTIC_STRONG_CONFIDENCE ->
                model
            model.confidence >= TFLITE_MEDIUM_CONFIDENCE && model.category == heuristic.category ->
                model.copy(confidence = max(model.confidence, heuristic.confidence))
            else -> heuristic
        }
    }

    private fun classifyWithTflite(text: String): CategoryScore? {
        val localInterpreter = interpreterOrNull() ?: return null
        if (categories.isEmpty()) return null
        val output = Array(1) { FloatArray(categories.size) }
        return runCatching {
            val input = buildTfliteInput(localInterpreter, text) ?: return null
            synchronized(interpreterLock) {
                localInterpreter.run(input, output)
            }
            val scores = output[0]
            val bestIndex = scores.indices.maxByOrNull { scores[it] } ?: return null
            CategoryScore(categories[bestIndex], scores[bestIndex], "tflite")
        }.onFailure {
            logger("TFLite note classification failed", it)
        }.getOrNull()
    }

    private fun buildTfliteInput(localInterpreter: Interpreter, text: String): Any? {
        val inputTensor = localInterpreter.getInputTensor(0)
        return when (inputTensor.dataType()) {
            DataType.STRING ->
                stringInputForShape(Summarizer.normalizeForModelInput(text), inputTensor.shape())
            DataType.INT32 ->
                arrayOf(Summarizer.tokenizeForModelInput(text, vocabulary))
            DataType.INT64 ->
                arrayOf(Summarizer.tokenizeForModelInput(text, vocabulary).map { it.toLong() }.toLongArray())
            else -> {
                Log.w(TAG, "Unsupported TFLite input type: ${inputTensor.dataType()}")
                null
            }
        }
    }

    private fun stringInputForShape(value: String, shape: IntArray): Any =
        when (shape.size) {
            0 -> value
            1 -> Array(shape[0].positiveTensorDim()) { value }
            2 -> Array(shape[0].positiveTensorDim()) {
                Array(shape[1].positiveTensorDim()) { value }
            }
            else -> Array(shape[0].positiveTensorDim()) {
                Array(shape.drop(1).fold(1) { acc, dim -> acc * dim.positiveTensorDim() }) { value }
            }
        }

    private fun interpreterOrNull(): Interpreter? {
        interpreter?.let { return it }
        if (interpreterLoadAttempted) return null
        synchronized(interpreterLock) {
            interpreter?.let { return it }
            if (interpreterLoadAttempted) return null
            interpreterLoadAttempted = true
            return runCatching {
                val bytes = assetLoader(appContext, Summarizer.MODEL_ASSET_NAME).use { it.readBytes() }
                val buffer = ByteBuffer.allocateDirect(bytes.size)
                    .order(ByteOrder.nativeOrder())
                    .put(bytes)
                buffer.rewind()
                Interpreter(buffer, Interpreter.Options().setNumThreads(TFLITE_THREADS)).also {
                    interpreter = it
                }
            }.onFailure {
                logger("Unable to load ${Summarizer.MODEL_ASSET_NAME}; using heuristic classifier", it)
            }.getOrNull()
        }
    }

    private fun loadVocabulary(): TokenizerVocabulary =
        runCatching {
            assetLoader(appContext, Summarizer.TOKENIZER_VOCAB_ASSET_NAME)
                .bufferedReader()
                .useLines { lines ->
                    TokenizerVocabulary.from(lines.map { it.trim() }.filter { it.isNotEmpty() }.toList())
                }
        }.getOrElse {
            logger("Unable to load ${Summarizer.TOKENIZER_VOCAB_ASSET_NAME}", it)
            TokenizerVocabulary.EMPTY
        }

    private fun loadCategories(): List<String> =
        runCatching {
            val json = assetLoader(appContext, Summarizer.CATEGORY_MAPPING_ASSET_NAME)
                .bufferedReader()
                .use { it.readText() }
            val arr = JSONObject(json).getJSONArray("categories")
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.getString(i))
                }
            }
        }.getOrElse {
            logger("Unable to load ${Summarizer.CATEGORY_MAPPING_ASSET_NAME}", it)
            HeuristicCategoryClassifier.DEFAULT_CATEGORIES
        }

    private data class CategoryScore(
        val category: String,
        val confidence: Float,
        val source: String,
    )

    internal data class NoteParts(
        val title: String,
        val body: String,
    ) {
        val fullText: String = listOf(title, body).filter { it.isNotBlank() }.joinToString(": ")

        companion object {
            fun parse(text: String): NoteParts {
                val cleaned = cleanWhitespace(text.replace(ATTACHMENT_TAG_REGEX, " "))
                if (cleaned.isBlank()) return NoteParts("", "")

                if (cleaned.startsWith("Title:", ignoreCase = true)) {
                    val withoutPrefix = cleaned.removePrefixIgnoringCase("Title:").trim()
                    val split = withoutPrefix.split("\n\n", limit = 2)
                    return if (split.size == 2) {
                        NoteParts(cleanTitle(split[0]), split[1].trim())
                    } else {
                        NoteParts("", withoutPrefix)
                    }
                }

                val colonIndex = cleaned.indexOf(':')
                if (colonIndex in 4..80) {
                    val maybeTitle = cleaned.substring(0, colonIndex).trim()
                    val body = cleaned.substring(colonIndex + 1).trim()
                    if (maybeTitle.wordCount() <= 9 && body.isNotBlank()) {
                        return NoteParts(cleanTitle(maybeTitle), body)
                    }
                }

                return NoteParts("", cleaned)
            }
        }
    }

    private object HeuristicCategoryClassifier {
        val DEFAULT_CATEGORIES = listOf(
            "FOOD_RECIPE",
            "PERSONAL_DAILY_LIFE",
            "FINANCE_LEGAL",
            "SELF_IMPROVEMENT",
            "HEALTH_WELLNESS",
            "EDUCATION_LEARNING",
            "HOME_FAMILY",
            "WORK_PROJECT",
            "MEETING_RECAP",
            "SHOPPING_LIST",
            "GENERAL_CHECKLIST",
            "REMINDER",
            "TRAVEL_LOG",
            "CREATIVE_WRITING",
            "TECHNICAL_REFERENCE",
        )

        private val categoryKeywords = mapOf(
            "SHOPPING_LIST" to listOf("grocery", "groceries", "shopping", "buy", "pick up", "carton", "jar", "refill"),
            "GENERAL_CHECKLIST" to listOf("checklist", "todo", "to-do", "task", "tasks", "steps", "complete"),
            "REMINDER" to listOf("remind", "reminder", "appointment", "call", "tomorrow", "schedule", "deadline"),
            "MEETING_RECAP" to listOf("meeting", "recap", "aligned", "discussed", "assigned", "action item", "follow-up"),
            "FOOD_RECIPE" to listOf("recipe", "tbsp", "tsp", "bake", "simmer", "cook", "ingredients", "curry", "dough"),
            "WORK_PROJECT" to listOf("project", "rollout", "milestone", "deliverable", "status", "implementation", "timeline"),
            "TECHNICAL_REFERENCE" to listOf("git", "command", "api", "config", "install", "error", "stack trace", "syntax"),
            "FINANCE_LEGAL" to listOf("budget", "invoice", "tax", "irs", "contract", "legal", "bank", "deposit", "$"),
            "HEALTH_WELLNESS" to listOf("doctor", "dentist", "miles", "pace", "workout", "sleep", "medicine", "wellness"),
            "TRAVEL_LOG" to listOf("trip", "flight", "hotel", "journey", "visited", "vacation", "travel", "amtrak"),
            "CREATIVE_WRITING" to listOf("poem", "poetry", "story", "novel", "chapter", "character", "scene", "sketch"),
            "EDUCATION_LEARNING" to listOf("class", "course", "study", "workshop", "learned", "training", "exam"),
            "HOME_FAMILY" to listOf("home", "family", "kids", "house", "hvac", "dryer", "water heater"),
            "SELF_IMPROVEMENT" to listOf("goal", "habit", "reflection", "journal", "personal growth", "routine"),
        )

        fun classify(parts: NoteParts): CategoryScore {
            val lower = parts.fullText.lowercase(Locale.US)
            val scores = DEFAULT_CATEGORIES.associateWith { 0f }.toMutableMap()
            val items = SummaryComposer.extractItems(parts.body)
            val bulletCount = parts.body.lines().count { BULLET_LINE_REGEX.containsMatchIn(it) }
            val commaCount = parts.body.count { it == ',' }

            categoryKeywords.forEach { (category, keywords) ->
                val score = keywords.sumOf { keyword ->
                    if (lower.contains(keyword)) {
                        if (keyword.length > 5) 2.0 else 1.0
                    } else {
                        0.0
                    }
                }.toFloat()
                scores[category] = (scores[category] ?: 0f) + score
            }

            if (items.size >= 4 && (lower.contains("buy") || lower.contains("grocery") || lower.contains("shopping"))) {
                scores["SHOPPING_LIST"] = scores.getValue("SHOPPING_LIST") + 4f
            }
            if (items.size >= 3 || bulletCount >= 2 || commaCount >= 4) {
                scores["GENERAL_CHECKLIST"] = scores.getValue("GENERAL_CHECKLIST") + 2f
            }
            if (Regex("\\b\\d{1,2}:\\d{2}\\b|\\b(am|pm)\\b|\\btomorrow\\b|\\bnext week\\b").containsMatchIn(lower)) {
                scores["REMINDER"] = scores.getValue("REMINDER") + 2f
            }
            if (Regex("\\$\\d|\\b\\d+(?:\\.\\d+)?\\s*(?:miles|minutes|hours|lbs|kg|calories)\\b").containsMatchIn(lower)) {
                scores["FINANCE_LEGAL"] = scores.getValue("FINANCE_LEGAL") + if (lower.contains("$")) 2f else 0f
                scores["HEALTH_WELLNESS"] = scores.getValue("HEALTH_WELLNESS") + if (lower.contains("miles") || lower.contains("pace")) 2f else 0f
            }

            val best = scores.maxByOrNull { it.value } ?: return CategoryScore("PERSONAL_DAILY_LIFE", 0f, "heuristic")
            return CategoryScore(best.key, best.value, "heuristic")
        }
    }

    private object SummaryComposer {
        private val categoryHints = mapOf(
            "SHOPPING_LIST" to listOf("buy", "pick", "grocery", "shopping", "need"),
            "GENERAL_CHECKLIST" to listOf("complete", "replace", "clean", "test", "document"),
            "REMINDER" to listOf("call", "schedule", "request", "mention", "send", "renew"),
            "MEETING_RECAP" to listOf("aligned", "assigned", "discussed", "noted", "decided", "follow"),
            "FOOD_RECIPE" to listOf("add", "mix", "toast", "simmer", "bake", "cook", "knead"),
            "WORK_PROJECT" to listOf("project", "rollout", "timeline", "assigned", "milestone"),
            "TECHNICAL_REFERENCE" to listOf("command", "configure", "install", "syntax", "error", "git"),
            "FINANCE_LEGAL" to listOf("budget", "reconciled", "deposit", "contract", "tax", "$"),
            "HEALTH_WELLNESS" to listOf("ran", "walked", "doctor", "dentist", "pace", "sleep"),
            "TRAVEL_LOG" to listOf("departed", "visited", "explored", "sampled", "hotel", "trip"),
            "CREATIVE_WRITING" to listOf("story", "scene", "chapter", "poem", "character", "sketch"),
            "EDUCATION_LEARNING" to listOf("learned", "completed", "practiced", "course", "workshop"),
            "HOME_FAMILY" to listOf("family", "home", "clean", "repair", "replace", "kids"),
            "SELF_IMPROVEMENT" to listOf("goal", "habit", "routine", "reflection", "improve"),
        )

        fun compose(parts: NoteParts, category: String): String {
            val title = parts.title.takeIf { it.isNotBlank() }
            val body = parts.body.ifBlank { parts.title }
            val summary = when (category) {
                "SHOPPING_LIST" -> itemSummary(title, "Shopping", body, maxItems = 5)
                "GENERAL_CHECKLIST" -> itemSummary(title, "Checklist", body, maxItems = 4)
                "REMINDER" -> actionSummary(title, "Reminder", body, category, maxFragments = 3)
                "MEETING_RECAP" -> actionSummary(title, "Meeting", body, category, maxFragments = 3)
                "FOOD_RECIPE" -> actionSummary(title, "Recipe", body, category, maxFragments = 3)
                "WORK_PROJECT" -> actionSummary(title, "Project", body, category, maxFragments = 3)
                "TECHNICAL_REFERENCE" -> actionSummary(title, "Reference", body, category, maxFragments = 2)
                "FINANCE_LEGAL" -> actionSummary(title, "Finance", body, category, maxFragments = 3)
                "HEALTH_WELLNESS" -> actionSummary(title, "Health", body, category, maxFragments = 3)
                "TRAVEL_LOG" -> actionSummary(title, "Travel", body, category, maxFragments = 3)
                "CREATIVE_WRITING" -> actionSummary(title, "Creative writing", body, category, maxFragments = 2)
                "EDUCATION_LEARNING" -> actionSummary(title, "Learning", body, category, maxFragments = 3)
                "HOME_FAMILY" -> actionSummary(title, "Home", body, category, maxFragments = 3)
                "SELF_IMPROVEMENT" -> actionSummary(title, "Personal growth", body, category, maxFragments = 2)
                else -> actionSummary(title, "Note", body, category, maxFragments = 2)
            }
            return Summarizer.smartTruncate(cleanWhitespace(summary), MAX_FAST_SUMMARY_CHARS)
        }

        fun extractItems(text: String): List<String> {
            val bulletItems = text.lines()
                .mapNotNull { line ->
                    BULLET_LINE_REGEX.find(line)?.let {
                        line.substring(it.range.last + 1).trim()
                    }
                }
                .filter { it.isNotBlank() }
            val rawItems = if (bulletItems.size >= 2) {
                bulletItems
            } else if (text.count { it == ',' } >= 2 || text.contains(';') || text.contains(" and ", ignoreCase = true)) {
                text.split(Regex("\\s*(?:,|;|\\band\\b)\\s*", RegexOption.IGNORE_CASE))
            } else {
                emptyList()
            }
            return rawItems
                .map { cleanFragment(it) }
                .filter { it.wordCount() in 1..12 }
                .distinctBy { it.lowercase(Locale.US) }
        }

        private fun itemSummary(title: String?, fallbackTitle: String, body: String, maxItems: Int): String {
            val anchor = cleanTitle(title).ifBlank { fallbackTitle }
            val items = extractItems(body)
            if (items.isEmpty()) return actionSummary(title, fallbackTitle, body, "GENERAL_CHECKLIST", 2)
            val shown = items.take(maxItems)
            val more = items.size - shown.size
            val suffix = if (more > 0) ", plus $more more" else ""
            return punctuate("$anchor: ${joinFragments(shown)}$suffix")
        }

        private fun actionSummary(
            title: String?,
            fallbackTitle: String,
            body: String,
            category: String,
            maxFragments: Int,
        ): String {
            val anchor = cleanTitle(title).ifBlank { fallbackTitle }
            val fragments = keyFragments(body, title.orEmpty(), category, maxFragments)
            if (fragments.isEmpty()) {
                val fallback = conciseFallback(body)
                return punctuate("$anchor: $fallback")
            }
            return punctuate("$anchor: ${joinFragments(fragments.map { compactFragment(it, category) })}")
        }

        private fun keyFragments(
            body: String,
            title: String,
            category: String,
            maxFragments: Int,
        ): List<String> {
            val candidates = splitIntoFragments(body)
                .map { cleanFragment(it) }
                .filter { it.wordCount() in 2..28 }
                .distinctBy { it.lowercase(Locale.US) }
            if (candidates.isEmpty()) return emptyList()

            val titleWords = importantWords(title)
            val hints = categoryHints[category].orEmpty()
            return candidates
                .mapIndexed { index, fragment ->
                    val lower = fragment.lowercase(Locale.US)
                    val hintScore = hints.count { lower.contains(it) } * 3
                    val titleScore = titleWords.count { lower.contains(it) } * 2
                    val numberScore = if (NUMBER_REGEX.containsMatchIn(fragment)) 2 else 0
                    val nameScore = if (PROPER_NOUN_REGEX.containsMatchIn(fragment)) 1 else 0
                    val positionScore = max(0, 3 - index)
                    fragment to (hintScore + titleScore + numberScore + nameScore + positionScore)
                }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { candidates.indexOf(it.first) })
                .take(maxFragments)
                .map { it.first }
                .sortedBy { candidates.indexOf(it) }
        }

        private fun splitIntoFragments(text: String): List<String> {
            val sentenceFragments = SENTENCE_REGEX.findAll(text)
                .map { it.value.trim() }
                .toList()
            val base = sentenceFragments.ifEmpty { listOf(text) }
            return base.flatMap { sentence ->
                sentence.split(Regex("\\s*(?:;|\\n+)\\s*"))
                    .flatMap { clause ->
                        if (clause.length > LONG_CLAUSE_SPLIT_LENGTH && clause.count { it == ',' } >= 1) {
                            clause.split(Regex("\\s*,\\s*"))
                        } else {
                            listOf(clause)
                        }
                    }
            }
        }

        private fun compactFragment(fragment: String, category: String): String {
            val cleaned = cleanFragment(fragment)
            val maxWords = when (category) {
                "TECHNICAL_REFERENCE", "CREATIVE_WRITING", "SELF_IMPROVEMENT" -> 10
                "SHOPPING_LIST", "GENERAL_CHECKLIST" -> 7
                else -> 12
            }
            return cleaned.limitWords(maxWords)
        }

        private fun conciseFallback(body: String): String {
            val firstUseful = splitIntoFragments(body)
                .map { cleanFragment(it) }
                .firstOrNull { it.wordCount() >= 2 }
                ?: body.trim()
            return firstUseful.limitWords(16)
        }

        private fun joinFragments(fragments: List<String>): String =
            when (fragments.size) {
                0 -> ""
                1 -> fragments[0]
                2 -> "${fragments[0]} and ${fragments[1]}"
                else -> fragments.dropLast(1).joinToString(", ") + ", and " + fragments.last()
            }
    }

    companion object {
        private const val TAG = "FastNoteSummarizer"
        private const val TFLITE_THREADS = 2
        private const val TFLITE_HIGH_CONFIDENCE = 0.78f
        private const val TFLITE_MEDIUM_CONFIDENCE = 0.58f
        private const val HEURISTIC_STRONG_CONFIDENCE = 3.0f
        private const val MAX_FAST_SUMMARY_CHARS = 140
        private const val LONG_CLAUSE_SPLIT_LENGTH = 72

        private val ATTACHMENT_TAG_REGEX = Regex("\\[\\[(?:image|file):\\d+]]")
        private val BULLET_LINE_REGEX = Regex("^\\s*(?:[-*+]|\\d+[.)]|\\[[ xX]])\\s+")
        private val SENTENCE_REGEX = Regex("[^.!?]+[.!?]?")
        private val NUMBER_REGEX = Regex("\\d")
        private val PROPER_NOUN_REGEX = Regex("\\b[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*\\b")
        private val LEADING_FILLER_REGEX = Regex(
            "^(?:need to|remember to|todo:?|to-do:?|buy|pick up|get|please|note:?|reminder to)\\s+",
            RegexOption.IGNORE_CASE,
        )
        private val GENERIC_TITLE_SUFFIX_REGEX = Regex(
            "\\s+\\b(?:recap|summary|note|notes)\\b\\s*$",
            RegexOption.IGNORE_CASE,
        )
        private val TRAILING_PUNCTUATION_REGEX = Regex("[\\s,.;:!-]+$")
        private val IMPORTANT_WORD_REGEX = Regex("[a-zA-Z][a-zA-Z0-9'-]{2,}")
        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "from", "that", "this", "note", "notes", "about",
            "into", "onto", "will", "need", "needs", "todo", "list", "meeting", "recap",
        )

        private fun cleanWhitespace(text: String): String =
            text.replace("\r\n", "\n")
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()

        private fun cleanTitle(title: String?): String =
            title.orEmpty()
                .removePrefixIgnoringCase("Title:")
                .replace(GENERIC_TITLE_SUFFIX_REGEX, "")
                .replace(TRAILING_PUNCTUATION_REGEX, "")
                .trim()

        private fun cleanFragment(fragment: String): String =
            fragment.replace(LEADING_FILLER_REGEX, "")
                .replace(Regex("\\s+"), " ")
                .replace(TRAILING_PUNCTUATION_REGEX, "")
                .trim()

        private fun String.limitWords(maxWords: Int): String {
            val words = trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size <= maxWords) return trim()
            return words.take(maxWords).joinToString(" ")
        }

        private fun punctuate(text: String): String {
            val cleaned = text.replace(TRAILING_PUNCTUATION_REGEX, "").trim()
            if (cleaned.isBlank()) return ""
            return if (cleaned.last() in ".!?") cleaned else "$cleaned."
        }

        private fun importantWords(text: String): Set<String> =
            IMPORTANT_WORD_REGEX.findAll(text.lowercase(Locale.US))
                .map { it.value }
                .filter { it !in STOP_WORDS }
                .toSet()

        private fun String.wordCount(): Int =
            trim().split(Regex("\\s+")).count { it.isNotBlank() }

        private fun String.removePrefixIgnoringCase(prefix: String): String =
            if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

        private fun Int.positiveTensorDim(): Int = if (this > 0) this else 1
    }
}
