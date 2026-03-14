package com.aria.launcher.aria.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SkillDao {

    // --- AppSkill queries ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkill(skill: AppSkill)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkills(skills: List<AppSkill>)

    @Query("SELECT * FROM app_skills WHERE enabled = 1 ORDER BY name ASC")
    fun observeEnabledSkills(): Flow<List<AppSkill>>

    @Query("SELECT * FROM app_skills ORDER BY name ASC")
    suspend fun getAllSkills(): List<AppSkill>

    @Query("SELECT * FROM app_skills WHERE enabled = 1 ORDER BY name ASC")
    suspend fun getEnabledSkills(): List<AppSkill>

    @Query("SELECT * FROM app_skills WHERE appPackage = :packageName")
    suspend fun getSkillsForApp(packageName: String): List<AppSkill>

    @Query("SELECT * FROM app_skills WHERE id = :skillId")
    suspend fun getSkillById(skillId: String): AppSkill?

    @Query("UPDATE app_skills SET enabled = :enabled WHERE id = :skillId")
    suspend fun setSkillEnabled(skillId: String, enabled: Boolean)

    @Query("DELETE FROM app_skills WHERE id = :skillId")
    suspend fun deleteSkill(skillId: String)

    // --- SkillResult queries ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResult(result: SkillResult)

    @Query(
        """
        SELECT * FROM skill_results
        WHERE skillId = :skillId
        ORDER BY timestamp DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestResult(skillId: String): SkillResult?

    @Query(
        """
        SELECT sr.* FROM skill_results sr
        INNER JOIN (
            SELECT skillId, MAX(timestamp) as maxTs
            FROM skill_results
            GROUP BY skillId
        ) latest ON sr.skillId = latest.skillId AND sr.timestamp = latest.maxTs
        WHERE sr.expiresAt IS NULL OR sr.expiresAt > :now
        ORDER BY sr.priority DESC
        """,
    )
    fun observeActiveResults(now: Long = System.currentTimeMillis()): Flow<List<SkillResult>>

    @Query(
        """
        SELECT sr.* FROM skill_results sr
        INNER JOIN (
            SELECT skillId, MAX(timestamp) as maxTs
            FROM skill_results
            GROUP BY skillId
        ) latest ON sr.skillId = latest.skillId AND sr.timestamp = latest.maxTs
        WHERE sr.expiresAt IS NULL OR sr.expiresAt > :now
        ORDER BY sr.priority DESC
        """,
    )
    suspend fun getActiveResults(now: Long = System.currentTimeMillis()): List<SkillResult>

    @Query("DELETE FROM skill_results WHERE expiresAt IS NOT NULL AND expiresAt < :now")
    suspend fun pruneExpiredResults(now: Long = System.currentTimeMillis())

    @Query("DELETE FROM skill_results WHERE skillId = :skillId")
    suspend fun deleteResultsForSkill(skillId: String)

    @Query("DELETE FROM skill_results")
    suspend fun deleteAllResults()
}
