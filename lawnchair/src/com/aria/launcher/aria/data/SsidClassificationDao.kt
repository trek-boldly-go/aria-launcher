// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SsidClassificationDao {

    @Query("SELECT * FROM ssid_classifications WHERE ssidHash = :hash LIMIT 1")
    suspend fun getByHash(hash: String): SsidClassification?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(classification: SsidClassification)

    @Query(
        """UPDATE ssid_classifications
           SET visitCount = visitCount + 1,
               lastSeen = :lastSeen,
               averageVisitDurationMinutes = (averageVisitDurationMinutes * visitCount + :durationMinutes) / (visitCount + 1),
               visitContext = :visitContext
           WHERE ssidHash = :hash""",
    )
    suspend fun recordVisit(
        hash: String,
        lastSeen: Long,
        durationMinutes: Long,
        visitContext: String,
    )

    @Query("SELECT * FROM ssid_classifications ORDER BY lastSeen DESC")
    suspend fun getAll(): List<SsidClassification>
}
