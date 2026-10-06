package com.srooyesh.seedcounter

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper


data class SeedSession(
    val id: Long,
    val variety: String,
    val customer: String,
    val dateKey: String,
    val createdAt: Long,
    val finishedAt: Long?,
    val active: Boolean
)

data class SeedRecord(
    val id: Long,
    val sessionId: Long,
    val number: String,
    val barcode: String,
    val time: String,
    val dateGregorian: String,
    val dateJalali: String
)

data class HistoryRow(
    val id: Long,
    val sessionId: Long,
    val variety: String,
    val customer: String,
    val number: String,
    val barcode: String,
    val dateGregorian: String,
    val dateJalali: String,
    val time: String
)

data class ExportRow(
    val sessionId: Long,
    val variety: String,
    val customer: String,
    val number: String,
    val barcode: String,
    val dateGregorian: String,
    val dateJalali: String,
    val time: String
)

class SeedDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                variety TEXT NOT NULL,
                customer TEXT NOT NULL DEFAULT '',
                date_key TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                finished_at INTEGER,
                active INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                packet_number TEXT NOT NULL DEFAULT '',
                barcode TEXT NOT NULL DEFAULT '',
                time_text TEXT NOT NULL,
                date_gregorian TEXT NOT NULL,
                date_jalali TEXT NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE meta (key_name TEXT PRIMARY KEY, value_text TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_sessions_date ON sessions(date_key)")
        db.execSQL("CREATE INDEX idx_sessions_active ON sessions(active)")
        db.execSQL("CREATE INDEX idx_sessions_customer ON sessions(customer)")
        db.execSQL("CREATE INDEX idx_records_session ON records(session_id)")
        db.execSQL("CREATE INDEX idx_records_number ON records(packet_number)")
        db.execSQL("CREATE INDEX idx_records_barcode ON records(barcode)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.beginTransaction()
        try {
            if (oldVersion < 2) {
                db.execSQL("INSERT OR REPLACE INTO meta(key_name, value_text) VALUES('schema_version','2')")
            }
            if (oldVersion < 3) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_customer ON sessions(customer)")
                db.execSQL("INSERT OR REPLACE INTO meta(key_name, value_text) VALUES('schema_version','3')")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Creates one active session without silently closing another active session. */
    fun createSession(
        variety: String,
        customer: String,
        dateKey: String,
        activeSessionErrorMessage: String
    ): SeedSession {
        val db = writableDatabase
        db.beginTransaction()
        val id: Long
        try {
            db.rawQuery("SELECT id FROM sessions WHERE active=1 LIMIT 1", null).use {
                if (it.moveToFirst()) error(activeSessionErrorMessage)
            }
            val values = ContentValues().apply {
                put("variety", variety)
                put("customer", customer)
                put("date_key", dateKey)
                put("created_at", System.currentTimeMillis())
                put("active", 1)
            }
            id = db.insertOrThrow("sessions", null, values)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return getSession(id) ?: error("Session creation failed")
    }

    fun getSession(id: Long): SeedSession? {
        val cursor = readableDatabase.query(
            "sessions",
            null,
            "id=?",
            arrayOf(id.toString()),
            null,
            null,
            null
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            return readSession(it)
        }
    }

    fun getActiveSession(): SeedSession? {
        val cursor = readableDatabase.query(
            "sessions",
            null,
            "active=1",
            null,
            null,
            null,
            "id DESC",
            "1"
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            return readSession(it)
        }
    }

    fun finishSession(sessionId: Long) {
        val values = ContentValues().apply {
            put("active", 0)
            put("finished_at", System.currentTimeMillis())
        }
        writableDatabase.update("sessions", values, "id=?", arrayOf(sessionId.toString()))
    }

    fun addRecord(
        sessionId: Long,
        packetNumber: String,
        barcode: String,
        dateGregorian: String,
        dateJalali: String,
        timeText: String
    ): Long {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("packet_number", packetNumber)
            put("barcode", barcode)
            put("time_text", timeText)
            put("date_gregorian", dateGregorian)
            put("date_jalali", dateJalali)
        }
        return writableDatabase.insertOrThrow("records", null, values)
    }

    fun isDuplicate(sessionId: Long, number: String, barcode: String): Boolean {
        val db = readableDatabase
        if (number.isNotBlank()) {
            db.query(
                "records",
                arrayOf("id"),
                "session_id=? AND packet_number=?",
                arrayOf(sessionId.toString(), number),
                null,
                null,
                null,
                "1"
            ).use { if (it.moveToFirst()) return true }
        }
        if (barcode.isNotBlank()) {
            db.query(
                "records",
                arrayOf("id"),
                "session_id=? AND barcode=?",
                arrayOf(sessionId.toString(), barcode),
                null,
                null,
                null,
                "1"
            ).use { if (it.moveToFirst()) return true }
        }
        return false
    }

    fun getRecordCount(sessionId: Long): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM records WHERE session_id=?",
            arrayOf(sessionId.toString())
        ).use { if (it.moveToFirst()) return it.getInt(0) }
        return 0
    }

    fun getRecords(sessionId: Long): List<SeedRecord> {
        val result = mutableListOf<SeedRecord>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, session_id, packet_number, barcode, time_text, date_gregorian, date_jalali FROM records WHERE session_id=? ORDER BY id ASC",
            arrayOf(sessionId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                result.add(
                    SeedRecord(
                        it.getLong(0), it.getLong(1), it.getString(2), it.getString(3),
                        it.getString(4), it.getString(5), it.getString(6)
                    )
                )
            }
        }
        return result
    }

    fun getAllRows(): List<ExportRow> {
        val result = mutableListOf<ExportRow>()
        val cursor = readableDatabase.rawQuery(
            """
            SELECT r.session_id, s.variety, s.customer, r.packet_number, r.barcode,
                   r.date_gregorian, r.date_jalali, r.time_text
            FROM records r
            INNER JOIN sessions s ON s.id = r.session_id
            ORDER BY r.date_gregorian ASC, r.id ASC
            """.trimIndent(),
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                result.add(
                    ExportRow(
                        it.getLong(0), it.getString(1), it.getString(2), it.getString(3),
                        it.getString(4), it.getString(5), it.getString(6), it.getString(7)
                    )
                )
            }
        }
        return result
    }

    fun getSessions(): List<SeedSession> {
        val result = mutableListOf<SeedSession>()
        readableDatabase.query("sessions", null, null, null, null, null, "date_key ASC, id ASC").use {
            while (it.moveToNext()) result.add(readSession(it))
        }
        return result
    }

    fun findSession(dateKey: String, variety: String, customer: String): SeedSession? {
        readableDatabase.query(
            "sessions", null,
            "date_key=? AND variety=? AND customer=?",
            arrayOf(dateKey, variety, customer), null, null, "id DESC", "1"
        ).use {
            if (!it.moveToFirst()) return null
            return readSession(it)
        }
    }

    fun insertImportedSession(session: SeedSession): Long {
        val values = ContentValues().apply {
            put("variety", session.variety)
            put("customer", session.customer)
            put("date_key", session.dateKey)
            put("created_at", session.createdAt)
            if (session.finishedAt != null) put("finished_at", session.finishedAt)
            put("active", 0)
        }
        return writableDatabase.insertOrThrow("sessions", null, values)
    }

    fun insertImportedRecord(record: SeedRecord, targetSessionId: Long): Long {
        val values = ContentValues().apply {
            put("session_id", targetSessionId)
            put("packet_number", record.number)
            put("barcode", record.barcode)
            put("time_text", record.time)
            put("date_gregorian", record.dateGregorian)
            put("date_jalali", record.dateJalali)
        }
        return writableDatabase.insertOrThrow("records", null, values)
    }

    fun searchHistory(query: String, limit: Int = 300): List<HistoryRow> {
        val q = Storage.normalizeSearchQuery(query)
        if (q.isBlank()) return emptyList()
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val like = "%$escaped%"
        val result = mutableListOf<HistoryRow>()
        readableDatabase.rawQuery(
            """
            SELECT r.id, r.session_id, s.variety, s.customer, r.packet_number, r.barcode,
                   r.date_gregorian, r.date_jalali, r.time_text
            FROM records r
            INNER JOIN sessions s ON s.id = r.session_id
            WHERE r.packet_number LIKE ? ESCAPE '\'
               OR r.barcode LIKE ? ESCAPE '\'
               OR s.variety LIKE ? ESCAPE '\'
               OR s.customer LIKE ? ESCAPE '\'
               OR r.date_gregorian LIKE ? ESCAPE '\'
               OR r.date_jalali LIKE ? ESCAPE '\'
            ORDER BY r.date_gregorian DESC, r.id DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(like, like, like, like, like, like, limit.coerceIn(1, 1000).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    HistoryRow(
                        id = cursor.getLong(0),
                        sessionId = cursor.getLong(1),
                        variety = cursor.getString(2),
                        customer = cursor.getString(3),
                        number = cursor.getString(4),
                        barcode = cursor.getString(5),
                        dateGregorian = cursor.getString(6),
                        dateJalali = cursor.getString(7),
                        time = cursor.getString(8)
                    )
                )
            }
        }
        return result
    }

    fun getTodaySummary(dateKey: String): Pair<Int, Int> {
        var sessions = 0
        var records = 0
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sessions WHERE date_key=?",
            arrayOf(dateKey)
        ).use { if (it.moveToFirst()) sessions = it.getInt(0) }
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM records r INNER JOIN sessions s ON r.session_id=s.id WHERE s.date_key=?",
            arrayOf(dateKey)
        ).use { if (it.moveToFirst()) records = it.getInt(0) }
        return sessions to records
    }

    fun deleteAll() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL("DELETE FROM records")
            writableDatabase.execSQL("DELETE FROM sessions")
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private fun readSession(c: android.database.Cursor): SeedSession = SeedSession(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        variety = c.getString(c.getColumnIndexOrThrow("variety")),
        customer = c.getString(c.getColumnIndexOrThrow("customer")),
        dateKey = c.getString(c.getColumnIndexOrThrow("date_key")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        finishedAt = if (c.isNull(c.getColumnIndexOrThrow("finished_at"))) null else c.getLong(c.getColumnIndexOrThrow("finished_at")),
        active = c.getInt(c.getColumnIndexOrThrow("active")) == 1
    )

    companion object {
        private const val DB_NAME = "seedcounter.db"
        private const val DB_VERSION = 3
        @Volatile private var instance: SeedDatabase? = null

        fun get(context: Context): SeedDatabase = instance ?: synchronized(this) {
            instance ?: SeedDatabase(context).also { instance = it }
        }

        fun close() {
            synchronized(this) {
                try { instance?.close() } catch (_: Exception) { }
                instance = null
            }
        }
    }
}

class SeedRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = SeedDatabase.get(appContext)
    private val legacyPrefs by lazy { appContext.getSharedPreferences(LegacyKeys.PREFS, Context.MODE_PRIVATE) }
    private val veryOldPrefs by lazy { appContext.getSharedPreferences("seed_data", Context.MODE_PRIVATE) }
    @Volatile private var migrationChecked = false
    private val migrationLock = Any()

    fun todayKey(): String = Storage.todayKey()

    fun getActiveSession(): SeedSession? {
        ensureLegacyMigration()
        return db.getActiveSession()
    }

    fun startSession(variety: String, customer: String): Result<SeedSession> = runCatching {
        ensureLegacyMigration()
        val active = getActiveSession()
        if (active != null) {
            if (active.variety == variety && active.customer == customer) return@runCatching active
            error(appContext.getString(R.string.active_session_exists))
        }
        db.createSession(
            variety = variety,
            customer = customer,
            dateKey = todayKey(),
            activeSessionErrorMessage = appContext.getString(R.string.active_session_exists)
        )
    }

    fun finishSession(sessionId: Long) { ensureLegacyMigration(); db.finishSession(sessionId) }

    fun getRecords(sessionId: Long): List<SeedRecord> { ensureLegacyMigration(); return db.getRecords(sessionId) }

    fun getRecordCount(sessionId: Long): Int { ensureLegacyMigration(); return db.getRecordCount(sessionId) }

    fun addRecord(sessionId: Long, rawNumber: String, rawBarcode: String, duplicateCheck: Boolean = true): Result<Long> = runCatching {
        ensureLegacyMigration()
        val number = Storage.normalizePacketNumber(rawNumber)
        val barcode = Storage.normalizeBarcode(rawBarcode)
        if (number.isBlank() && barcode.isBlank()) error(appContext.getString(R.string.no_scan_data))
        if (number.isNotBlank() && (number.length !in 2..16)) error(appContext.getString(R.string.invalid_packet_number_range, 2, 16))
        if (barcode.isNotBlank() && barcode.length > 120) error(appContext.getString(R.string.barcode_too_long))
        if (duplicateCheck && db.isDuplicate(sessionId, number, barcode)) error(appContext.getString(R.string.duplicate_packet))
        val session = db.getSession(sessionId) ?: error(appContext.getString(R.string.invalid_session))
        val now = java.util.Date()
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(now)
        // Records belong to the session's business date, not necessarily today's date.
        val gregorian = session.dateKey
        val jalali = jalaliForDateKey(session.dateKey)
        db.addRecord(sessionId, number, barcode, gregorian, jalali, time)
    }

    fun getAllRows(): List<ExportRow> { ensureLegacyMigration(); return db.getAllRows() }
    fun searchHistory(query: String): List<HistoryRow> { ensureLegacyMigration(); return db.searchHistory(query) }
    fun getSessions(): List<SeedSession> { ensureLegacyMigration(); return db.getSessions() }

    fun getTodaySummary(dateKey: String = Storage.todayKey()): Pair<Int, Int> {
        ensureLegacyMigration()
        return db.getTodaySummary(dateKey)
    }

    fun deleteAll() {
        ensureLegacyMigration()
        db.deleteAll()
        legacyPrefs.edit().clear().apply()
        veryOldPrefs.edit().clear().apply()
    }

    fun exportBackupJson(): String {
        ensureLegacyMigration()
        return BackupCodec.export(db, appContext, SettingsStore(appContext))
    }

    fun importBackupJson(json: String): Result<Int> = runCatching {
        ensureLegacyMigration()
        BackupCodec.importAndMerge(db, SettingsStore(appContext), json)
    }

    private fun ensureLegacyMigration() {
        if (migrationChecked) return
        synchronized(migrationLock) {
            if (migrationChecked) return
            migrateLegacyDataIfNeeded()
            migrationChecked = true
        }
    }

    private fun migrateLegacyDataIfNeeded() {
        val marker = legacyPrefs.getBoolean("db_migrated_v3", false)
        if (marker) return

        val dbRows = db.getAllRows()
        if (dbRows.isNotEmpty()) {
            legacyPrefs.edit().putBoolean("db_migrated_v3", true).apply()
            return
        }

        val sessionMap = mutableMapOf<String, Long>()
        db.writableDatabase.beginTransaction()
        try {
            legacyPrefs.all.keys.filter { it.startsWith(LegacyKeys.PREFIX) }.sorted().forEach { key ->
                val parts = key.split("|", limit = 3)
                if (parts.size != 3) return@forEach
                val dateKey = parts[1]
                val variety = Storage.decodeVariety(parts[2])
                val sessionKey = "$dateKey\u0000$variety\u0000"
                val sessionId = sessionMap.getOrPut(sessionKey) {
                    db.createSessionDirect(variety, "", dateKey, active = false)
                }
                val json = legacyPrefs.getString(key, null) ?: return@forEach
                try {
                    val array = org.json.JSONArray(json)
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val n = Storage.normalizePacketNumber(obj.optString("number"))
                        if (n.length !in 2..16) continue
                        if (!db.isDuplicate(sessionId, n, "")) {
                            db.addRecord(
                                sessionId, n, "", dateKey,
                                jalaliForDateKey(dateKey), obj.optString("time")
                            )
                        }
                    }
                } catch (_: Exception) {
                }
            }

            veryOldPrefs.all.keys.forEach { key ->
                val underscore = key.indexOf('_')
                if (underscore <= 0) return@forEach
                val dateKey = key.substring(0, underscore)
                val variety = key.substring(underscore + 1)
                val sessionKey = "$dateKey\u0000$variety\u0000"
                val sessionId = sessionMap.getOrPut(sessionKey) {
                    db.createSessionDirect(variety, "", dateKey, active = false)
                }
                val value = veryOldPrefs.getString(key, null) ?: return@forEach
                value.split(',').map { Storage.normalizePacketNumber(it) }.filter { it.length in 2..16 }.distinct().forEach { n ->
                    if (!db.isDuplicate(sessionId, n, "")) {
                        db.addRecord(sessionId, n, "", dateKey, jalaliForDateKey(dateKey), "")
                    }
                }
            }

            db.setActiveForLegacy(
                dateKey = legacyPrefs.getString(LegacyKeys.KEY_ACTIVE_DATE, "").orEmpty(),
                variety = legacyPrefs.getString(LegacyKeys.KEY_ACTIVE_VARIETY, "").orEmpty()
            )

            db.setMetaDirect("schema_version", "2")
            db.setMetaDirect("migration_complete", "true")
            db.writableDatabase.setTransactionSuccessful()
        } finally {
            db.writableDatabase.endTransaction()
        }

        legacyPrefs.edit().putBoolean("db_migrated_v3", true).apply()
    }

    internal fun jalaliForDateKey(dateKey: String): String = try {
        val p = dateKey.split("-")
        PersianDate.gregorianToJalali(p[0].toInt(), p[1].toInt(), p[2].toInt()).let {
            "%04d/%02d/%02d".format(it.first, it.second, it.third)
        }
    } catch (_: Exception) {
        ""
    }
}

object Storage {
    fun todayKey(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    @Deprecated("Legacy storage key retained only for migration compatibility", level = DeprecationLevel.WARNING)
    fun recordKey(date: String, variety: String): String {
        val encoded = android.util.Base64.encodeToString(
            variety.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        return "${LegacyKeys.PREFIX}$date|$encoded"
    }

    fun decodeVariety(encoded: String): String = try {
        String(android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING), Charsets.UTF_8)
    } catch (_: Exception) { encoded }

    fun normalizePacketNumber(input: String): String {
        val fa = "۰۱۲۳۴۵۶۷۸۹"
        val ar = "٠١٢٣٤٥٦٧٨٩"
        return buildString {
            input.forEach { c ->
                val fi = fa.indexOf(c)
                val ai = ar.indexOf(c)
                when {
                    fi >= 0 -> append(fi)
                    ai >= 0 -> append(ai)
                    c in '0'..'9' -> append(c)
                }
            }
        }
    }

    fun normalizeSearchQuery(input: String): String {
        val fa = "۰۱۲۳۴۵۶۷۸۹"
        val ar = "٠١٢٣٤٥٦٧٨٩"
        return buildString(input.length) {
            input.trim().forEach { c ->
                val fi = fa.indexOf(c)
                val ai = ar.indexOf(c)
                when {
                    fi >= 0 -> append(('0'.code + fi).toChar())
                    ai >= 0 -> append(('0'.code + ai).toChar())
                    else -> append(c)
                }
            }
        }.trim()
    }

    fun normalizeBarcode(input: String): String {
        val fa = "۰۱۲۳۴۵۶۷۸۹"
        val ar = "٠١٢٣٤٥٦٧٨٩"
        return buildString {
            input.trim().forEach { c ->
                val fi = fa.indexOf(c)
                val ai = ar.indexOf(c)
                when {
                    fi >= 0 -> append(fi)
                    ai >= 0 -> append(ai)
                    c.isWhitespace() -> Unit
                    else -> append(c)
                }
            }
        }.trim()
    }
}

// Package-private migration helpers kept on the database object so future schema changes stay centralized.
private fun SeedDatabase.createSessionDirect(variety: String, customer: String, dateKey: String, active: Boolean): Long {
    val values = ContentValues().apply {
        put("variety", variety); put("customer", customer); put("date_key", dateKey)
        put("created_at", System.currentTimeMillis()); put("active", if (active) 1 else 0)
    }
    return writableDatabase.insertOrThrow("sessions", null, values)
}

private fun SeedDatabase.setMetaDirect(key: String, value: String) {
    writableDatabase.execSQL("INSERT OR REPLACE INTO meta(key_name,value_text) VALUES(?,?)", arrayOf(key, value))
}

private fun SeedDatabase.setActiveForLegacy(dateKey: String, variety: String) {
    if (dateKey.isBlank() || variety.isBlank()) return
    writableDatabase.execSQL("UPDATE sessions SET active=0 WHERE active=1")
    writableDatabase.execSQL(
        "UPDATE sessions SET active=1 WHERE id=(SELECT id FROM sessions WHERE date_key=? AND variety=? ORDER BY id DESC LIMIT 1)",
        arrayOf(dateKey, variety)
    )
}
