package app.budgetguard.android

import android.app.Application
import app.budgetguard.android.data.BudgetGuardDatabase
import app.budgetguard.android.data.TransactionRepository
import app.budgetguard.android.sync.CollectorStatusStore
import app.budgetguard.android.sync.SupabaseCollectorClient
import app.budgetguard.android.sync.SyncScheduler

class BudgetGuardApplication : Application() {
    lateinit var repository: TransactionRepository
        private set
    lateinit var statusStore: CollectorStatusStore
        private set
    val collectorClient: SupabaseCollectorClient? by lazy {
        SupabaseCollectorClient.create(this)
    }

    override fun onCreate() {
        super.onCreate()
        repository = TransactionRepository(BudgetGuardDatabase.create(this).transactions())
        statusStore = CollectorStatusStore(this)
        SyncScheduler.ensurePeriodicSync(this)
    }
}
