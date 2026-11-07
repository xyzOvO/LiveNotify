package com.example.livenotify

import com.example.livenotify.data.AppDatabase
import com.example.livenotify.util.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * 数据库迁移的自动化验证。
 *
 * 关注列表是长期积累的个人数据，迁移写错一列就是静默丢数据。这里用真实 SQLite
 * （sqlite-jdbc）从 v1 / v2 / v3 的表结构分别跑到最新版，然后断言两件事：
 *
 * 1. **最终列结构与 Room 导出的 schema JSON 完全一致**——将来给 [StreamerEntity]
 *    加了字段却忘了写迁移，测试会直接红掉，而不是在用户手机上静默丢列；
 * 2. **老数据一条不少**：暂停状态（enabled）、通知去重（lastNotifiedLiveStart /
 *    lastNotifiedAt）这些最容易在重建表时丢掉的字段，逐行核对。
 *
 * 迁移 SQL 与生产共用同一份 `AppDatabase.MIGRATION_SQL`，不存在「测的是副本」的问题。
 */
class MigrationSqlTest {

    // ── Room 导出的最新 schema（迁移的终点必须与它逐列一致） ──

    private data class ColumnSpec(val name: String, val notNull: Boolean, val defaultValue: String?)

    private fun schemaFile(): File {
        // AGP 的单测工作目录是模块目录；实在不在就往上层找一遍（不同 Gradle 配置的差异）
        val relative = "schemas/com.example.livenotify.data.AppDatabase/4.json"
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("schema JSON 不存在（先跑一次 assembleDebug 让 KSP 导出）：${candidates.map { it.absolutePath }}")
        return file
    }

    private fun expectedColumns(): Map<String, ColumnSpec> {
        val root = MiniJson.parse(schemaFile().readText()) as Map<*, *>
        val db = root["database"] as Map<*, *>
        assertEquals("schema 版本应与 AppDatabase.version 一致", 4L, asLong(db["version"]))
        val entity = (db["entities"] as List<*>)
            .map { it as Map<*, *> }
            .single { it["tableName"] == "streamers" }
        return (entity["fields"] as List<*>).map { it as Map<*, *> }.associate {
            val name = it["columnName"] as String
            name to ColumnSpec(
                name = name,
                notNull = it["notNull"] == true,
                // Room 导出的 defaultValue 带原文（如 "0"、"''"），PRAGMA 返回同款原文
                defaultValue = it["defaultValue"] as String?
            )
        }
    }

    private fun asLong(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        else -> error("不是数字：$v")
    }

    // ── v1 的基线表（历史版本的起点）──

    private fun createV1() = """
        CREATE TABLE IF NOT EXISTS `streamers` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `platformId` TEXT NOT NULL,
            `anchorId` TEXT NOT NULL,
            `displayId` TEXT NOT NULL,
            `nickname` TEXT NOT NULL,
            `avatarUrl` TEXT,
            `title` TEXT NOT NULL,
            `roomUrl` TEXT NOT NULL,
            `isLive` INTEGER NOT NULL,
            `liveStreak` INTEGER NOT NULL,
            `liveStartAt` INTEGER NOT NULL,
            `lastNotifiedAt` INTEGER NOT NULL,
            `lastNotifiedLiveStart` INTEGER NOT NULL,
            `lastCheckedAt` INTEGER NOT NULL,
            `missing` INTEGER NOT NULL,
            `errorCount` INTEGER NOT NULL,
            `lastError` TEXT,
            `enabled` INTEGER NOT NULL
        )
    """.trimIndent()

    /** 所有历史版本都存在的列；种子数据只写这些，避免基线版本差异导致插入失败 */
    private fun seedRows() = listOf(
        // 正常关注中、已通知过一场：验证通知去重字段逐字保留
        listOf(
            "platformId" to "bilibili", "anchorId" to "a1", "displayId" to "21452505",
            "nickname" to "七海Nana7mi", "avatarUrl" to null as String?, "title" to "咩噗",
            "roomUrl" to "https://live.bilibili.com/21452505",
            "isLive" to 0, "liveStreak" to 0, "liveStartAt" to 0L,
            "lastNotifiedAt" to 999L, "lastNotifiedLiveStart" to 555L,
            "lastCheckedAt" to 111L, "missing" to 0, "errorCount" to 0,
            "enabled" to 1
        ),
        // 用户手动暂停的主播：暂停状态最容易被「迁移时重建表」弄丢
        listOf(
            "platformId" to "douyu", "anchorId" to "71415", "displayId" to "71415",
            "nickname" to "寅子", "avatarUrl" to null as String?, "title" to "",
            "roomUrl" to "https://www.douyu.com/71415",
            "isLive" to 1, "liveStreak" to 2, "liveStartAt" to 777L,
            "lastNotifiedAt" to 42L, "lastNotifiedLiveStart" to 777L,
            "lastCheckedAt" to 222L, "missing" to 0, "errorCount" to 0,
            "enabled" to 0
        )
    )

    /** JDBC 没有现成的 Connection#executeUpdate，包一层 */
    private fun Connection.executeUpdate(sql: String) {
        createStatement().use { it.executeUpdate(sql) }
    }

    private fun Connection.createBaseline(version: Int) {
        // 基线 = v1 建表 + 按版本依次应用迁移 SQL（与当年用户数据库的演进路径一致）
        val history = listOf(1 to 2, 2 to 3, 3 to 4).take(version - 1)
        executeUpdate(createV1())
        executeUpdate(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_streamers_platformId_anchorId` " +
                "ON `streamers` (`platformId`, `anchorId`)"
        )
        history.forEach { step ->
            AppDatabase.MIGRATION_SQL.getValue(step).forEach { executeUpdate(it) }
        }
        // 基线建好后才插种子：显式列出所有历史版本都有的列
        seedRows().forEach { row ->
            val cols = row.joinToString(", ") { it.first }
            val marks = row.joinToString(", ") { "?" }
            prepareStatement("INSERT INTO streamers ($cols) VALUES ($marks)").use { ps ->
                row.forEachIndexed { i, (_, value) ->
                    when (value) {
                        null -> ps.setNull(i + 1, java.sql.Types.VARCHAR)
                        is String -> ps.setString(i + 1, value)
                        is Long -> ps.setLong(i + 1, value)
                        is Int -> ps.setInt(i + 1, value)
                        else -> error("不支持的种子类型：$value")
                    }
                }
                assertEquals(1, ps.executeUpdate())
            }
        }
    }

    private fun Connection.migrateFrom(version: Int) {
        var v = version
        while (v < 4) {
            val next = v + 1
            AppDatabase.MIGRATION_SQL.getValue(v to next).forEach { executeUpdate(it) }
            v = next
        }
        // user_version 也要推进，模拟 Room 打开时的真实路径
        executeUpdate("PRAGMA user_version = 4")
    }

    private fun Connection.tableColumns(): Map<String, Triple<Boolean, String?, Int>> =
        createStatement().executeQuery("PRAGMA table_info(streamers)").use { rs ->
            val out = linkedMapOf<String, Triple<Boolean, String?, Int>>()
            while (rs.next()) {
                out[rs.getString("name")] = Triple(
                    rs.getInt("notnull") == 1,
                    rs.getString("dflt_value"),
                    rs.getInt("pk")
                )
            }
            out
        }

    private fun Connection.streamers(): List<Map<String, Any?>> =
        createStatement().executeQuery("SELECT * FROM streamers ORDER BY platformId").use { rs ->
            val meta = rs.metaData
            buildList {
                while (rs.next()) {
                    add((1..meta.columnCount).associate {
                        meta.getColumnName(it) to rs.getObject(it)
                    })
                }
            }
        }

    private fun assertMigratedStructure(conn: Connection) {
        val expected = expectedColumns()
        val actual = conn.tableColumns()
        assertEquals(
            "迁移后的列集合与 Room schema 不一致（多半是加了字段忘了写迁移）",
            expected.keys, actual.keys
        )
        expected.forEach { (name, spec) ->
            val (notNull, default, pk) = actual.getValue(name)
            assertEquals("$name NOT NULL 不一致", spec.notNull, notNull)
            // Room 的容忍规则：实体侧没有声明 defaultValue 时不比较（迁移 SQL 带
            // `DEFAULT 0` 而实体只有 Kotlin 默认值是合法组合，Room 校验同样放行）
            if (spec.defaultValue != null) {
                assertEquals("$name 默认值不一致", spec.defaultValue, default)
            }
            if (name == "id") assertEquals("id 必须仍是主键", 1, pk)
        }
    }

    private fun assertDataSurvived(conn: Connection) {
        val rows = conn.streamers()
        assertEquals("两行种子数据都必须保留", 2, rows.size)

        val nana = rows.first { it["nickname"] == "七海Nana7mi" }
        assertEquals("通知去重标记不能丢", 555L, asLong(nana["lastNotifiedLiveStart"]))
        assertEquals("冷却时间不能丢", 999L, asLong(nana["lastNotifiedAt"]))
        assertEquals("关注状态不能丢", 1L, asLong(nana["enabled"]))
        // 新列必须带上默认值，而不是 NULL
        assertEquals(0L, asLong(nana["pendingNotifySession"]))
        assertEquals(0L, asLong(nana["pendingNotifySince"]))

        val paused = rows.first { it["nickname"] == "寅子" }
        assertEquals("暂停状态不能丢", 0L, asLong(paused["enabled"]))
        assertEquals("直播中的状态不能丢", 1L, asLong(paused["isLive"]))
        assertEquals("场次 key 不能丢", 777L, asLong(paused["liveStartAt"]))
        assertEquals(0L, asLong(paused["pendingNotifySession"]))
    }

    @Test
    fun `从 v1 升级到最新版结构与数据完整`() = verifyUpgradeFrom(1)

    @Test
    fun `从 v2 升级到最新版结构与数据完整`() = verifyUpgradeFrom(2)

    @Test
    fun `从 v3 升级到最新版结构与数据完整`() = verifyUpgradeFrom(3)

    private fun verifyUpgradeFrom(version: Int) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { it.executeUpdate("PRAGMA foreign_keys = ON") }
            conn.createBaseline(version)
            conn.migrateFrom(version)
            assertMigratedStructure(conn)
            assertDataSurvived(conn)
        }
    }

    @Test
    fun `迁移映射覆盖到当前版本且连续`() {
        val steps = AppDatabase.MIGRATION_SQL.keys
        // 从 1 到 4 每一步都要有 SQL，否则 Room 打开老库会直接崩
        for (v in 1 until 4) {
            assertTrue("缺少 v$v 的迁移 SQL", steps.contains(v to v + 1))
        }
        assertEquals(3, steps.size)
    }
}
