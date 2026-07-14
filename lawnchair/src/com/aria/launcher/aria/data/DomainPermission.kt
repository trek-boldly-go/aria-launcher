// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "domain_permissions")
data class DomainPermission(
    @PrimaryKey val domain: String,
    val allowedMethods: String,
    val createdAt: Long,
)

@Dao
interface DomainPermissionDao {

    @Query("SELECT * FROM domain_permissions WHERE domain = :domain")
    suspend fun findByDomain(domain: String): DomainPermission?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(permission: DomainPermission)

    @Query("DELETE FROM domain_permissions WHERE domain = :domain")
    suspend fun delete(domain: String)

    @Query("SELECT * FROM domain_permissions ORDER BY domain")
    suspend fun getAll(): List<DomainPermission>
}
