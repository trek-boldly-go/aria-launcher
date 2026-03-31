// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Represents a detected app-open chain: [trigger] app is consistently followed
 * by [followUp] app within a short time window.
 *
 * Stored after nightly [AppChainDetector] pass. Queried at unlock time in
 * [AriaHomeState] to boost follow-up predictions when the trigger was recently used.
 */
@Entity(primaryKeys = ["trigger", "followUp"])
data class AppChain(
    val trigger: String, // packageName of the app that starts the chain
    val followUp: String, // packageName that consistently follows
    val occurrences: Int,
)

@Dao
interface AppChainDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chains: List<AppChain>)

    @Query("SELECT * FROM AppChain WHERE `trigger` = :triggerPackage ORDER BY occurrences DESC")
    suspend fun getChainsByTrigger(triggerPackage: String): List<AppChain>

    @Query("SELECT * FROM AppChain ORDER BY occurrences DESC")
    suspend fun getAllChains(): List<AppChain>

    @Query("DELETE FROM AppChain")
    suspend fun deleteAll()
}
