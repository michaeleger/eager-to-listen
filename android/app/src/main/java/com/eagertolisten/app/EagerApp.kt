package com.eagertolisten.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.eagertolisten.app.data.EagerDatabase
import com.eagertolisten.app.data.repository.PodcastRepository
import com.eagertolisten.app.data.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EagerApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database by lazy { EagerDatabase.getInstance(this) }
    val repository by lazy { PodcastRepository(this) }

    companion object {
        lateinit var instance: EagerApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                val syncMgr = SyncManager(this@EagerApp)
                if (syncMgr.state.value.folderUri != null && syncMgr.state.value.autoSyncEnabled) {
                    appScope.launch(Dispatchers.IO) {
                        try {
                            syncMgr.performSync()
                        } catch (_: Exception) {}
                    }
                }
            }
        })
    }
}
