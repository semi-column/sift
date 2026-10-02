package app.sift

import android.app.Application
import android.content.Context
import app.sift.backend.Access
import app.sift.data.HistoryStore
import app.sift.data.Repository
import app.sift.data.Store
import app.sift.data.Updates
import app.sift.engine.BulkEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var store: Store
    lateinit var history: HistoryStore
    lateinit var access: Access
    lateinit var repo: Repository
    lateinit var engine: BulkEngine
    lateinit var updates: Updates

    override fun onCreate() {
        super.onCreate()
        store = Store(this, scope)
        history = HistoryStore(this, scope)
        access = Access(this)
        repo = Repository(this)
        engine = BulkEngine(this)
        updates = Updates(this)
    }

    companion object {
        fun of(context: Context) = context.applicationContext as App
    }
}
