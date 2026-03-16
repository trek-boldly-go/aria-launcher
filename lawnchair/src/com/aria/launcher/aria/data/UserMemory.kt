// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.data

import androidx.room.Dao
import androidx.room.Entity
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
    val source: String = "chat", // "chat" or "onboarding"
)

@Dao
interface UserMemoryDao {

    @Query("SELECT * FROM user_memory ORDER BY lastReferencedAt DESC")
    suspend fun getAll(): List<UserMemory>

    @Query("SELECT * FROM user_memory ORDER BY lastReferencedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<UserMemory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: UserMemory): Long

    @Query("UPDATE user_memory SET lastReferencedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM user_memory WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM user_memory")
    suspend fun count(): Int

    @Query("SELECT * FROM user_memory WHERE fact LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<UserMemory>
}
