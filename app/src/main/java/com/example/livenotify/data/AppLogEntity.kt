package com.example.livenotify.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 运行日志（诊断用）。
 *
 * 只记「发生了什么」，不记主播名 / 台词原文 / Cookie：
 * anchorId 写平台侧 ID（本身就是数字/短号），message 经 [com.example.livenotify.monitor.AppLogger.redact]
 * 过滤后再落库。7 天 + 5000 条上限由 DAO 的清理语句维持。
 */
@Entity(
    tableName = "app_logs",
    indices = [Index(value = ["atMs"]), Index(value = ["category"])]
)
data class AppLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 事件时刻（毫秒） */
    val atMs: Long,
    /** 分类：DETECT / NOTIFY / SERVICE / NETWORK */
    val category: String,
    /** 级别：INFO / WARN / ERROR */
    val severity: String,
    /** 已脱敏的说明 */
    val message: String,
    /** 平台侧主播 ID（可空；全局事件留空） */
    val anchorId: String? = null,
    /** 平台 ID（可空） */
    val platformId: String? = null
)
