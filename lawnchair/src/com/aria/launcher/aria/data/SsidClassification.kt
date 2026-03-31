// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@TypeConverters(VenueCategoryConverter::class, VisitContextConverter::class)
@Entity(tableName = "ssid_classifications")
data class SsidClassification(
    @PrimaryKey val ssidHash: String, // SHA-256 of SSID for privacy
    val rawSsid: String, // stored locally only
    val venueCategory: VenueCategory,
    val visitContext: VisitContext = VisitContext.UNKNOWN,
    val visitCount: Int = 0,
    val averageVisitDurationMinutes: Long = 0,
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val userConfirmed: Boolean = false,
)

class VenueCategoryConverter {
    @TypeConverter
    fun fromVenueCategory(value: VenueCategory): String = value.name

    @TypeConverter
    fun toVenueCategory(value: String): VenueCategory = runCatching { VenueCategory.valueOf(value) }.getOrDefault(VenueCategory.UNKNOWN)
}

class VisitContextConverter {
    @TypeConverter
    fun fromVisitContext(value: VisitContext): String = value.name

    @TypeConverter
    fun toVisitContext(value: String): VisitContext = runCatching { VisitContext.valueOf(value) }.getOrDefault(VisitContext.UNKNOWN)
}
