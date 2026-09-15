package com.example.medicinekeeper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicineDao {
    @Query("SELECT * FROM medicines WHERE itemType = :itemType ORDER BY expiryDate ASC")
    fun observeByType(itemType: String): Flow<List<Medicine>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(medicine: Medicine)

    @Query("DELETE FROM medicines WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE medicines SET reminderSent = 1 WHERE id = :id")
    suspend fun markReminderSent(id: Long)

    @Query("SELECT * FROM medicines WHERE itemType = 'medicine' AND reminderSent = 0 AND expiryDate BETWEEN :from AND :to")
    suspend fun expiringBetween(from: Long, to: Long): List<Medicine>
}
