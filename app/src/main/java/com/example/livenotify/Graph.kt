package com.example.livenotify

import com.example.livenotify.data.AppDatabase
import com.example.livenotify.data.SettingsRepository
import com.example.livenotify.monitor.MonitorEngine

/** 极简手工依赖图（本项目规模无需引入 Hilt） */
object Graph {
    lateinit var db: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var engine: MonitorEngine
        private set

    fun init(context: android.content.Context) {
        db = AppDatabase.get(context)
        settings = SettingsRepository(context)
        engine = MonitorEngine(context, db, settings)
    }
}
