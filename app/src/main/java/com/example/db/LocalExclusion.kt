package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exclusions")
data class LocalExclusion(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val pattern: String,
    val isRegex: Boolean = false,
    val enabled: Boolean = true
)
