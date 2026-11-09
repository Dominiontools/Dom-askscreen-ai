package com.example

import android.app.Application
import com.example.data.local.AppDatabase
import com.example.data.local.PreferencesManager
import com.example.data.repository.AskScreenRepository

class AskScreenApp : Application() {
    lateinit var preferencesManager: PreferencesManager
        private set

    lateinit var repository: AskScreenRepository
        private set

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferencesManager = PreferencesManager(this)
        database = AppDatabase.getDatabase(this)
        repository = AskScreenRepository(this)
    }

    companion object {
        lateinit var instance: AskScreenApp
            private set
    }
}
