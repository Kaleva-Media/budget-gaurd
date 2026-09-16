package app.budgetguard.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(transaction: LocalTransactionEntity): Long

    @Query("select count(*) from local_transactions")
    fun observeCount(): Flow<Int>

    @Query("select count(*) from local_transactions where sync_state <> 'synced'")
    fun observePendingSyncCount(): Flow<Int>

    @Query("select * from local_transactions where sync_state <> 'synced' order by captured_at_epoch_ms asc limit :limit")
    suspend fun pendingSync(limit: Int = 50): List<LocalTransactionEntity>

    @Query("update local_transactions set sync_state = 'synced' where id = :id")
    suspend fun markSynced(id: Long)
}
