package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Content-free failure suppression metadata; no history or generated summary text. */
@Entity(tableName = "compaction_failures")
internal data class CompactionFailureEntity(
    @PrimaryKey val key: String,
    val sourceDigest: String,
    val policyVersion: Int,
    val algorithmVersion: Int,
    val failureCode: String,
    val failedAtMs: Long,
    val requestBytes: Long,
    val estimatedTokens: Int,
)

@Dao
internal interface CompactionFailureDao {
    @Query("SELECT * FROM compaction_failures WHERE `key` = :key")
    fun load(key: String): CompactionFailureEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(record: CompactionFailureEntity)

    @Query("DELETE FROM compaction_failures WHERE `key` = :key")
    fun clear(key: String)
}
