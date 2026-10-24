package com.eagertolisten.app

import android.app.Application
import com.eagertolisten.app.data.EagerDatabase
import com.eagertolisten.app.data.repository.PodcastRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    }
}
