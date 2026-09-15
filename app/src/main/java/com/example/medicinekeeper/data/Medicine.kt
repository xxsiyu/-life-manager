package com.example.medicinekeeper.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "medicines")
data class Medicine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val quantity: Int,
    val productionDate: Long,
    val shelfLifeDays: Int,
    val shelfLifeValue: Int = 0,
    val shelfLifeUnit: String = "天",
    val expiryDate: Long,
    val photo: ByteArray? = null,
    val itemType: String = "medicine",
    val reminderSent: Boolean = false
)
