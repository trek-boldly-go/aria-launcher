// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "user_memory")
data class UserMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fact: String,
    val category: String, // e.g. "preference", "routine", "person", "place", "general"
    val createdAt: Long = System.currentTimeMillis(),
    val lastReferencedAt: Long = System.currentTimeMillis(),
    val source: String = "chat", // "chat", "onboarding", or "agent"
)

/**
 * FTS4 mirror of [UserMemory.fact] keyed by the parent row id. Lets
 * [MemoryRepository] retrieve memories by content overlap with the current
 * user message rather than just "most recent N rows", and serves as the
 * pre-filter for the Jaccard-based dedup heuristic on new inserts.
 *
 * The FTS table is maintained explicitly (insert/delete on the parent triggers
 * matching FTS DAO calls). Using a standalone FTS table — rather than Room's
 * `contentEntity` mode — avoids the external-content "rebuild" gotcha and keeps
 * sync logic in one place: [MemoryRepository].
 */
@Entity(tableName = "user_memory_fts")
@Fts4
data class UserMemoryFts(
    @PrimaryKey @ColumnInfo(name = "rowid")
    val rowid: Int,
    val fact: String,
)

@Dao
interface UserMemoryDao {

    @Query("SELECT * FROM user_memory ORDER BY lastReferencedAt DESC")
    suspend fun getAll(): List<UserMemory>

    @Query("SELECT * FROM user_memory ORDER BY lastReferencedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<UserMemory>

    @Query("SELECT * FROM user_memory WHERE category = :category ORDER BY lastReferencedAt DESC")
    suspend fun getByCategory(category: String): List<UserMemory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: UserMemory): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(fts: UserMemoryFts)

    @Query("DELETE FROM user_memory_fts WHERE rowid = :id")
    suspend fun deleteFts(id: Long)

    @Query("DELETE FROM user_memory_fts")
    suspend fun deleteAllFts()

    @Query("UPDATE user_memory SET lastReferencedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_memory SET lastReferencedAt = :timestamp WHERE id IN (:ids)")
    suspend fun touchAll(ids: List<Long>, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM user_memory WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM user_memory")
    suspend fun deleteAll()

    /**
     * Deletes all but the [keepCount] most-recently-referenced memories.
     * Used by [MemoryRepository.evictIfOverCap] to bound table growth.
     */
    @Query(
        "DELETE FROM user_memory WHERE id NOT IN (" +
            "SELECT id FROM user_memory ORDER BY lastReferencedAt DESC LIMIT :keepCount)",
    )
    suspend fun deleteOldestKeepingRecent(keepCount: Int)

    @Query("SELECT COUNT(*) FROM user_memory")
    suspend fun count(): Int

    @Query("SELECT * FROM user_memory WHERE fact LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<UserMemory>

    /**
     * Full-text search backed by [UserMemoryFts]. Tokens are matched with the
     * default FTS4 tokenizer (whitespace + punctuation). Pass an FTS-style
     * query string — single words and prefix matches (e.g. "alle*") are safe.
     */
    @Query(
        "SELECT user_memory.* FROM user_memory " +
            "JOIN user_memory_fts ON user_memory.id = user_memory_fts.rowid " +
            "WHERE user_memory_fts MATCH :ftsQuery " +
            "ORDER BY user_memory.lastReferencedAt DESC LIMIT :limit",
    )
    suspend fun searchFts(ftsQuery: String, limit: Int): List<UserMemory>
}
