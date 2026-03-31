// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.engine

import android.util.Log
import com.aria.launcher.aria.data.AppSkill
import com.aria.launcher.aria.data.BuiltInSkills
import com.aria.launcher.aria.data.SkillDao
import com.aria.launcher.aria.data.SkillResult
import com.aria.launcher.aria.engine.skills.AgentSkillManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@Singleton
class SkillOrchestrator @Inject constructor(
    private val skillDao: SkillDao,
    private val executorRegistry: SkillExecutorRegistry,
    private val agentSkillManager: AgentSkillManager,
) {

    fun observeActiveResults(): Flow<List<SkillResult>> {
        return skillDao.observeActiveResults()
    }

    /** Seed built-in skills if the table is empty (first launch). */
    private suspend fun ensureSkillsSeeded() {
        val existing = skillDao.getAllSkills()
        if (existing.isEmpty()) {
            Log.d(TAG, "No skills found, seeding built-in skills")
            skillDao.insertSkills(BuiltInSkills.all())
        }

        // Seed scheduled agentskills that aren't yet in the database
        agentSkillManager.ensureInitialized()
        val scheduledSkills = agentSkillManager.getScheduledSkills()
        val existingIds = skillDao.getAllSkills().map { it.id }.toSet()
        for (skill in scheduledSkills) {
            if (skill.name !in existingIds) {
                val appSkill = AppSkill(
                    id = skill.name,
                    appPackage = "",
                    name = skill.name,
                    description = skill.description,
                    triggerType = "SCHEDULED",
                    sourceType = "EXTERNAL_HTTP",
                    contextMatch = skill.ariaContext,
                    refreshIntervalMin = skill.ariaRefresh,
                    enabled = true,
                )
                skillDao.insertSkill(appSkill)
                Log.d(TAG, "Seeded scheduled agentskill: ${skill.name}")
            }
        }
    }

    suspend fun executeMatchingSkills(currentTimeBucket: String) {
        withContext(Dispatchers.IO) {
            ensureSkillsSeeded()
            val enabledSkills = skillDao.getEnabledSkills()
            val now = System.currentTimeMillis()

            skillDao.pruneExpiredResults()

            for (skill in enabledSkills) {
                val executor = executorRegistry.getExecutor(skill.id)
                if (executor == null) {
                    Log.d(TAG, "No executor for skill: ${skill.id}")
                    continue
                }

                // Check context match
                if (skill.contextMatch.isNotBlank()) {
                    val matchBuckets = skill.contextMatch.split(",").map { it.trim() }
                    if (currentTimeBucket !in matchBuckets) {
                        Log.d(TAG, "Skipping ${skill.id}: context $currentTimeBucket not in $matchBuckets")
                        continue
                    }
                }

                // Check freshness
                val latestResult = skillDao.getLatestResult(skill.id)
                if (latestResult != null && skill.refreshIntervalMin > 0) {
                    val ageMs = now - latestResult.timestamp
                    val refreshMs = skill.refreshIntervalMin * 60 * 1000L
                    if (ageMs < refreshMs) {
                        Log.d(TAG, "Skipping ${skill.id}: result still fresh (${ageMs / 1000}s old)")
                        continue
                    }
                }

                // Execute
                try {
                    val result = executor.execute(skill)
                    if (result != null) {
                        skillDao.insertResult(result)
                        Log.d(TAG, "Skill ${skill.id} produced result: ${result.title}")
                    } else {
                        Log.d(TAG, "Skill ${skill.id} produced no result")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Skill ${skill.id} failed", e)
                }
            }
        }
    }

    suspend fun executeAllSkills() {
        withContext(Dispatchers.IO) {
            val enabledSkills = skillDao.getEnabledSkills()
            Log.d(TAG, "executeAllSkills: ${enabledSkills.size} enabled skills")
            skillDao.pruneExpiredResults()

            for (skill in enabledSkills) {
                val executor = executorRegistry.getExecutor(skill.id) ?: continue
                try {
                    val result = executor.execute(skill)
                    if (result != null) {
                        skillDao.insertResult(result)
                        Log.d(TAG, "Skill ${skill.id} produced result: ${result.title}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Skill ${skill.id} failed", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ARIA.SkillOrchestrator"
    }
}
