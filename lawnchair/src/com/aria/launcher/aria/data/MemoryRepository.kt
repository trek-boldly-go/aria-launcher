// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.data

import android.util.Log
import com.aria.launcher.aria.llm.AriaPrompts
import com.aria.launcher.aria.llm.ChatMessage
import com.aria.launcher.aria.llm.LlmProvider
import com.aria.launcher.aria.llm.LlmResult
import com.aria.launcher.aria.llm.Role
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Outcome of an attempted memory insert. */
sealed class InsertResult {
    data class Inserted(val id: Long) : InsertResult()
    data object Duplicate : InsertResult()
    data object Invalid : InsertResult()
}

/**
 * Owns all memory persistence + retrieval logic. ChatState should never touch
 * [UserMemoryDao] directly — go through this repository so dedup, eviction,
 * touch-on-read, and extraction gating stay consistent.
 */
@Singleton
class MemoryRepository @Inject constructor(
    private val dao: UserMemoryDao,
    private val ariaPreferences: AriaPreferences,
) {

    @Volatile private var lastExtractionAt: Long = 0L

    /**
     * Returns memories most relevant to [currentUserMessage]. Falls back to
     * recency when FTS yields nothing (cold start, or message has no useful
     * tokens). Touches the returned IDs so they bubble up next time too.
     */
    suspend fun recentForContext(
        currentUserMessage: String,
        limit: Int = 15,
    ): List<UserMemory> = withContext(Dispatchers.IO) {
        val ftsQuery = buildFtsQuery(currentUserMessage)
        val ftsResults = if (ftsQuery != null) {
            try {
                dao.searchFts(ftsQuery, limit)
            } catch (e: Exception) {
                Log.w(TAG, "FTS search failed: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }

        val memories = if (ftsResults.size >= MIN_FTS_HITS_BEFORE_FALLBACK) {
            ftsResults
        } else {
            // Backfill with recent so we never starve the prompt of context.
            val recent = dao.getRecent(limit)
            (ftsResults + recent).distinctBy { it.id }.take(limit)
        }

        if (memories.isNotEmpty()) {
            try {
                dao.touchAll(memories.map { it.id })
            } catch (_: Exception) { }
        }
        memories
    }

    suspend fun all(): List<UserMemory> = withContext(Dispatchers.IO) { dao.getAll() }

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dao.delete(id)
        dao.deleteFts(id)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
        dao.deleteAllFts()
    }

    /**
     * Inserts a new memory unless a near-duplicate already exists.
     *
     * Dedup uses an FTS pre-filter to find candidate matches, then computes
     * Jaccard token overlap. Above [DEDUP_OVERLAP_THRESHOLD] is treated as the
     * same fact. This catches paraphrases that the previous 30-char-substring
     * check missed (e.g. "User likes coffee" vs "Prefers coffee").
     */
    suspend fun insertIfNovel(
        fact: String,
        category: String,
        source: String = "chat",
    ): InsertResult = withContext(Dispatchers.IO) {
        val trimmed = fact.trim()
        if (trimmed.length !in MIN_FACT_LENGTH..MAX_FACT_LENGTH) return@withContext InsertResult.Invalid

        val ftsQuery = buildFtsQuery(trimmed)
        val candidates = if (ftsQuery != null) {
            try {
                dao.searchFts(ftsQuery, DEDUP_CANDIDATE_LIMIT)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val newTokens = tokenize(trimmed)
        val isDuplicate = candidates.any { existing ->
            jaccard(newTokens, tokenize(existing.fact)) > DEDUP_OVERLAP_THRESHOLD ||
                existing.fact.equals(trimmed, ignoreCase = true)
        }
        if (isDuplicate) return@withContext InsertResult.Duplicate

        val id = dao.insert(
            UserMemory(
                fact = trimmed,
                category = category,
                source = source,
            ),
        )
        dao.insertFts(UserMemoryFts(rowid = id.toInt(), fact = trimmed))
        evictIfOverCap()
        InsertResult.Inserted(id)
    }

    /**
     * Best single FTS match for [query]. Used by the chat agent's `forget` tool
     * to look up the memory the user is referring to before deletion.
     */
    suspend fun findByQuery(query: String): UserMemory? = withContext(Dispatchers.IO) {
        val ftsQuery = buildFtsQuery(query) ?: return@withContext null
        try {
            dao.searchFts(ftsQuery, 1).firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun evictIfOverCap(cap: Int = MAX_MEMORIES) = withContext(Dispatchers.IO) {
        val total = dao.count()
        if (total > cap) {
            // Snapshot the surviving IDs, drop oldest, then rebuild FTS to match.
            // Simpler than tracking which IDs were deleted.
            val survivors = dao.getRecent(cap).map { it.id to it.fact }
            dao.deleteOldestKeepingRecent(cap)
            dao.deleteAllFts()
            survivors.forEach { (id, fact) ->
                dao.insertFts(UserMemoryFts(rowid = id.toInt(), fact = fact))
            }
            Log.d(TAG, "Evicted ${total - cap} memories (cap=$cap)")
        }
    }

    /**
     * Decide whether the current chat tail is worth running an extraction LLM
     * call against. Returns false on trivial exchanges so we don't waste tokens
     * on every "ok"/"thanks" message.
     */
    fun shouldExtract(recentMessages: List<Pair<Role, String>>): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastExtractionAt < EXTRACTION_DEBOUNCE_MS) return false

        val userText = recentMessages
            .filter { it.first == Role.USER }
            .joinToString(" ") { it.second }
        if (userText.length < MIN_USER_TEXT_FOR_EXTRACTION) return false

        val lower = userText.lowercase()
        return EXTRACTION_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * Run memory extraction over the recent chat tail and persist any novel
     * facts. No-ops when the master memory toggle is off, when [shouldExtract]
     * gates the call, or when the LLM returns nothing parseable.
     */
    suspend fun runExtraction(
        provider: LlmProvider,
        recentMessages: List<Pair<Role, String>>,
    ): Int {
        if (!ariaPreferences.memoryEnabled.first()) return 0
        if (recentMessages.size < 2) return 0
        if (!shouldExtract(recentMessages)) return 0
        lastExtractionAt = System.currentTimeMillis()

        val existing = withContext(Dispatchers.IO) { dao.getRecent(30) }.map { it.fact }
        val prompt = AriaPrompts.buildMemoryExtractionPrompt(
            recentMessages = recentMessages.map { "${it.first.name}: ${it.second}" },
            existingMemories = existing,
        )

        val result = withContext(Dispatchers.IO) {
            provider.complete(
                systemPrompt = prompt,
                messages = listOf(
                    ChatMessage(
                        role = Role.USER,
                        content = "Extract noteworthy memories from the conversation above.",
                    ),
                ),
            )
        }

        if (result !is LlmResult.Text) return 0
        return parseAndStore(result.content)
    }

    /**
     * Convenience wrapper used by the `remember` chat tool. Forces source = "agent"
     * and skips extraction gating — the agent is intentionally storing this fact.
     */
    suspend fun rememberFromAgent(fact: String, category: String): InsertResult =
        insertIfNovel(fact, category, source = "agent")

    private suspend fun parseAndStore(response: String): Int {
        var inserted = 0
        for (rawLine in response.lines()) {
            val line = rawLine.trim()
            if (line.isBlank()) continue
            if (line.startsWith("NONE", ignoreCase = true)) continue

            val match = CATEGORY_REGEX.find(line)
            val (category, fact) = if (match != null) {
                match.groupValues[1].lowercase() to match.groupValues[2].trim()
            } else {
                "general" to line
            }
            when (val r = insertIfNovel(fact, category)) {
                is InsertResult.Inserted -> {
                    inserted++
                    Log.d(TAG, "Stored memory [$category]: $fact (id=${r.id})")
                }
                else -> { /* duplicate or invalid — silent */ }
            }
        }
        return inserted
    }

    private fun buildFtsQuery(text: String): String? {
        val tokens = tokenize(text).filter { it.length >= MIN_TOKEN_LENGTH }
        if (tokens.isEmpty()) return null
        // Use OR so a partial topic match still surfaces results.
        return tokens.joinToString(" OR ") { "$it*" }
    }

    private fun tokenize(text: String): Set<String> = text
        .lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotBlank() && it !in STOPWORDS }
        .toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return intersection / union
    }

    companion object {
        private const val TAG = "ARIA.Memory"
        private const val MAX_MEMORIES = 500
        private const val MIN_FACT_LENGTH = 5
        private const val MAX_FACT_LENGTH = 300
        private const val MIN_TOKEN_LENGTH = 3
        private const val MIN_FTS_HITS_BEFORE_FALLBACK = 5
        private const val DEDUP_CANDIDATE_LIMIT = 8
        private const val DEDUP_OVERLAP_THRESHOLD = 0.6
        private const val EXTRACTION_DEBOUNCE_MS = 60_000L
        private const val MIN_USER_TEXT_FOR_EXTRACTION = 50

        private val CATEGORY_REGEX = Regex("""\[(\w+)](.+)""")

        private val EXTRACTION_KEYWORDS = listOf(
            "i ",
            "i'",
            "my ",
            "me ",
            "like",
            "prefer",
            "always",
            "every",
            "never",
            "remember",
            "favorite",
            "usually",
            "hate",
        )

        private val STOPWORDS = setOf(
            "the", "and", "for", "are", "but", "not", "you", "your", "with", "from",
            "this", "that", "have", "has", "had", "was", "were", "will", "what",
            "when", "where", "who", "how", "why", "can", "could", "should", "would",
            "they", "them", "their", "there", "here", "into", "than", "then",
            "about", "just", "also", "some", "any", "all",
        )
    }
}

