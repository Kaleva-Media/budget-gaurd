package app.budgetguard.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [LocalTransactionEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class BudgetGuardDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao

    companion object {
        @Volatile private var instance: BudgetGuardDatabase? = null

        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_transactions ADD COLUMN institution TEXT NOT NULL DEFAULT 'absa'")
            }
        }

        fun create(context: Context): BudgetGuardDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                BudgetGuardDatabase::class.java,
                "budget-guard.db",
            ).addMigrations(migration1To2)
                .build()
                .also { instance = it }
        }
    }
}
