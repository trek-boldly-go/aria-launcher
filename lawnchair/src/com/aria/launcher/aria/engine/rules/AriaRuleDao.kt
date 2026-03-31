// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.engine.rules

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface AriaRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: AriaRule): Long

    @Update
    suspend fun update(rule: AriaRule)

    @Delete
    suspend fun delete(rule: AriaRule)

    /** Synchronous query for the evaluator (called on IO thread). */
    @Query("SELECT * FROM aria_rules WHERE isEnabled = 1")
    fun getEnabledRules(): List<AriaRule>

    @Query("SELECT * FROM aria_rules ORDER BY createdAt DESC")
    suspend fun getAllRules(): List<AriaRule>

    @Query("SELECT * FROM aria_rules WHERE id = :id")
    suspend fun getById(id: Long): AriaRule?

    @Query(
        """
        UPDATE aria_rules
        SET triggerCount = triggerCount + 1, lastTriggeredAt = :now
        WHERE id = :ruleId
        """,
    )
    fun incrementTriggerCount(ruleId: Long, now: Long = System.currentTimeMillis())
}
