// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.engine.skills

import android.content.Context
import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Parsed agentskills entry from a SKILL.md file.
 * Follows the [agentskills spec](https://agentskills.io/specification).
 */
data class AgentSkillEntry(
    val name: String,
    val description: String,
    val path: File,
    val sourceUrl: String? = null,
    val ariaTrigger: String = "heartbeat",
    val ariaContext: String = "",
    val ariaRefresh: Int = 15,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Discovers, installs, and manages agentskills-format skills.
 *
 * Skills are directories containing a `SKILL.md` file, stored in
 * `context.filesDir/skills/`. The SKILL.md follows the agentskills spec:
 * YAML frontmatter (name, description, metadata) + markdown instructions.
 */
@Singleton
class AgentSkillManager @Inject constructor(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private val skillsDir: File get() = File(context.filesDir, "skills")
    private val catalog = mutableListOf<AgentSkillEntry>()
    private val mutex = Mutex()
    private var initialized = false

    /** Scan the skills directory and build the in-memory catalog. */
    suspend fun ensureInitialized() {
        if (initialized) return
        mutex.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                skillsDir.mkdirs()
                copyBundledSkills()
                scanSkills()
            }
            initialized = true
        }
    }

    /** Returns name+description pairs for LLM injection (tier 1 disclosure). */
    suspend fun getSkillCatalog(): List<AgentSkillEntry> {
        ensureInitialized()
        return catalog.toList()
    }

    /** Returns heartbeat-triggered skills only. */
    suspend fun getHeartbeatSkills(): List<AgentSkillEntry> {
        ensureInitialized()
        return catalog.filter { it.ariaTrigger == "heartbeat" }
    }

    /** Returns scheduled skills only. */
    suspend fun getScheduledSkills(): List<AgentSkillEntry> {
        ensureInitialized()
        return catalog.filter { it.ariaTrigger == "scheduled" }
    }

    /** Returns full SKILL.md body content for activation (tier 2 disclosure). */
    suspend fun getSkillContent(name: String): String? {
        ensureInitialized()
        val entry = catalog.find { it.name == name } ?: return null
        val skillMd = File(entry.path, "SKILL.md")
        if (!skillMd.exists()) return null
        return withContext(Dispatchers.IO) {
            val content = skillMd.readText()
            stripFrontmatter(content)
        }
    }

    /** Get user config for a skill from SharedPreferences. */
    fun getConfig(skillName: String): Map<String, String> {
        val prefs = context.getSharedPreferences("aria_skill_config", Context.MODE_PRIVATE)
        val prefix = "skill.$skillName."
        return prefs.all
            .filter { it.key.startsWith(prefix) }
            .mapKeys { it.key.removePrefix(prefix) }
            .mapValues { it.value.toString() }
    }

    /** Set a config value for a skill. */
    fun setConfig(skillName: String, key: String, value: String) {
        val prefs = context.getSharedPreferences("aria_skill_config", Context.MODE_PRIVATE)
        prefs.edit().putString("skill.$skillName.$key", value).apply()
    }

    /** Install a skill from a URL pointing to a SKILL.md file. */
    suspend fun installFromUrl(url: String): Result<AgentSkillEntry> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }
            val content = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response"))

            val entry = installFromText(content, sourceUrl = url)
            entry
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install skill from URL: $url", e)
            Result.failure(e)
        }
    }

    /** Install a skill from raw SKILL.md text. */
    suspend fun installFromText(
        content: String,
        sourceUrl: String? = null,
    ): Result<AgentSkillEntry> = withContext(Dispatchers.IO) {
        try {
            val frontmatter = parseFrontmatter(content)
            val name = frontmatter["name"]
                ?: return@withContext Result.failure(Exception("Missing 'name' in SKILL.md frontmatter"))
            if (frontmatter["description"].isNullOrBlank()) {
                return@withContext Result.failure(Exception("Missing 'description' in SKILL.md frontmatter"))
            }

            // Validate name format per agentskills spec
            if (!name.matches(Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?\$")) || "--" in name) {
                return@withContext Result.failure(
                    Exception("Invalid skill name '$name': must be lowercase alphanumeric with hyphens"),
                )
            }

            val dir = File(skillsDir, name)
            dir.mkdirs()
            File(dir, "SKILL.md").writeText(content)

            // Store source URL for future updates
            if (sourceUrl != null) {
                File(dir, ".source_url").writeText(sourceUrl)
            }

            val entry = parseSkillEntry(dir) ?: return@withContext Result.failure(
                Exception("Failed to parse installed skill"),
            )

            mutex.withLock {
                catalog.removeAll { it.name == name }
                catalog.add(entry)
            }

            Log.d(TAG, "Installed skill: ${entry.name} (${entry.ariaTrigger})")
            Result.success(entry)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install skill", e)
            Result.failure(e)
        }
    }

    /** Uninstall a skill by name. */
    suspend fun uninstall(name: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(skillsDir, name)
        if (!dir.exists()) return@withContext false
        dir.deleteRecursively()
        mutex.withLock { catalog.removeAll { it.name == name } }

        // Clean up config
        val prefs = context.getSharedPreferences("aria_skill_config", Context.MODE_PRIVATE)
        val prefix = "skill.$name."
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()

        Log.d(TAG, "Uninstalled skill: $name")
        true
    }

    /** Copy bundled skills from assets to filesDir on first launch. */
    private fun copyBundledSkills() {
        try {
            val assetSkills = context.assets.list("skills") ?: return
            for (skillName in assetSkills) {
                val targetDir = File(skillsDir, skillName)
                if (targetDir.exists()) continue // Don't overwrite user-modified skills

                val assetFiles = context.assets.list("skills/$skillName") ?: continue
                targetDir.mkdirs()
                for (file in assetFiles) {
                    context.assets.open("skills/$skillName/$file").use { input ->
                        File(targetDir, file).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                Log.d(TAG, "Copied bundled skill: $skillName")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy bundled skills (non-fatal)", e)
        }
    }

    /** Scan filesDir/skills/ for SKILL.md directories. */
    private fun scanSkills() {
        catalog.clear()
        val dirs = skillsDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in dirs) {
            val entry = parseSkillEntry(dir)
            if (entry != null) {
                catalog.add(entry)
                Log.d(TAG, "Discovered skill: ${entry.name} (${entry.ariaTrigger})")
            }
        }
        Log.d(TAG, "Skill catalog: ${catalog.size} skills discovered")
    }

    /** Parse a single skill directory into an AgentSkillEntry. */
    private fun parseSkillEntry(dir: File): AgentSkillEntry? {
        val skillMd = File(dir, "SKILL.md")
        if (!skillMd.exists()) return null

        val content = skillMd.readText()
        val frontmatter = parseFrontmatter(content)
        val name = frontmatter["name"] ?: dir.name
        val description = frontmatter["description"] ?: return null

        // Read stored source URL for updates
        val sourceUrl = File(dir, ".source_url").takeIf { it.exists() }?.readText()?.trim()

        return AgentSkillEntry(
            name = name,
            description = description,
            path = dir,
            sourceUrl = sourceUrl,
            ariaTrigger = frontmatter["aria-trigger"] ?: "heartbeat",
            ariaContext = frontmatter["aria-context"] ?: "",
            ariaRefresh = frontmatter["aria-refresh"]?.toIntOrNull() ?: 15,
            metadata = frontmatter,
        )
    }

    /**
     * Parse YAML frontmatter from SKILL.md content.
     * Handles the agentskills format: `---` delimited YAML at the top of the file.
     * Supports nested metadata fields by flattening them with the parent key prefix.
     */
    private fun parseFrontmatter(content: String): Map<String, String> {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) return emptyMap()

        val endIndex = trimmed.indexOf("---", startIndex = 3)
        if (endIndex == -1) return emptyMap()

        val yaml = trimmed.substring(3, endIndex).trim()
        val result = mutableMapOf<String, String>()
        var currentKey: String? = null

        for (line in yaml.lines()) {
            // Indented line under metadata: block
            if (line.startsWith("  ") && currentKey == "metadata") {
                val metaLine = line.trim()
                val colonIdx = metaLine.indexOf(':')
                if (colonIdx > 0) {
                    val key = metaLine.substring(0, colonIdx).trim()
                    val value = metaLine.substring(colonIdx + 1).trim()
                        .removeSurrounding("\"")
                    // Flatten metadata keys — also store as top-level for aria-* fields
                    result[key] = value
                }
                continue
            }

            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) continue

            val key = line.substring(0, colonIdx).trim()
            val value = line.substring(colonIdx + 1).trim()
                .removeSurrounding("\"")

            currentKey = key
            if (value.isNotEmpty()) {
                result[key] = value
            }
        }

        return result
    }

    /** Strip YAML frontmatter, returning only the markdown body. */
    private fun stripFrontmatter(content: String): String {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) return trimmed
        val endIndex = trimmed.indexOf("---", startIndex = 3)
        if (endIndex == -1) return trimmed
        return trimmed.substring(endIndex + 3).trim()
    }

    companion object {
        private const val TAG = "ARIA.AgentSkills"
    }
}
