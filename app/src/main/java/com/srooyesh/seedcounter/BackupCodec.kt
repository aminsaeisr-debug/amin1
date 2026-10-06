package com.srooyesh.seedcounter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object BackupCodec {
    private const val MAX_IMPORT_RECORDS = 50_000
    private const val MAX_IMPORT_SESSIONS = 5_000

    fun export(db: SeedDatabase, context: Context, settings: SettingsStore): String {
        val root = JSONObject()
            .put("app", "seed-mas")
            .put("backupVersion", 3)
            .put("createdAt", System.currentTimeMillis())
        val sessions = JSONArray()
        val records = JSONArray()
        db.getSessions().forEach { s ->
            sessions.put(JSONObject()
                .put("id", s.id)
                .put("variety", s.variety)
                .put("customer", s.customer)
                .put("dateKey", s.dateKey)
                .put("createdAt", s.createdAt)
                .put("finishedAt", s.finishedAt ?: JSONObject.NULL)
                .put("active", s.active)
            )
        }
        db.getAllRows().forEach { r ->
            records.put(JSONObject()
                .put("sessionId", r.sessionId)
                .put("variety", r.variety)
                .put("customer", r.customer)
                .put("number", r.number)
                .put("barcode", r.barcode)
                .put("dateGregorian", r.dateGregorian)
                .put("dateJalali", r.dateJalali)
                .put("time", r.time)
            )
        }
        root.put("sessions", sessions)
        root.put("records", records)
        root.put("settings", JSONObject(settings.raw()))
        return root.toString(2)
    }

    fun importAndMerge(db: SeedDatabase, settings: SettingsStore, json: String): Int {
        val root = JSONObject(json)
        val sessionsJson = root.optJSONArray("sessions") ?: JSONArray()
        val recordsJson = root.optJSONArray("records") ?: JSONArray()
        if (sessionsJson.length() > MAX_IMPORT_SESSIONS) {
            error("تعداد جلسات پشتیبان بیش از حد مجاز است (حداکثر ۵۰۰۰).")
        }
        if (recordsJson.length() > MAX_IMPORT_RECORDS) {
            error("تعداد رکوردهای پشتیبان بیش از حد مجاز است (حداکثر ۵۰۰۰۰).")
        }
        val idMap = mutableMapOf<Long, Long>()
        var imported = 0
        val settingsToWrite = mutableMapOf<String, Any>()

        db.writableDatabase.beginTransaction()
        try {
            for (i in 0 until sessionsJson.length()) {
                val o = sessionsJson.optJSONObject(i) ?: continue
                val oldId = o.optLong("id", -1)
                if (oldId <= 0) continue
                val dateKey = o.optString("dateKey")
                val variety = o.optString("variety")
                val customer = o.optString("customer")
                val existing = db.findSession(dateKey, variety, customer)
                val target = existing?.id ?: db.insertImportedSession(
                    SeedSession(
                        id = oldId,
                        variety = variety,
                        customer = customer,
                        dateKey = dateKey,
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        finishedAt = if (o.isNull("finishedAt")) null else o.optLong("finishedAt"),
                        active = false
                    )
                )
                idMap[oldId] = target
            }
            for (i in 0 until recordsJson.length()) {
                val o = recordsJson.optJSONObject(i) ?: continue
                val target = idMap[o.optLong("sessionId", -1)] ?: continue
                val n = Storage.normalizePacketNumber(o.optString("number"))
                val b = Storage.normalizeBarcode(o.optString("barcode"))
                if (n.isBlank() && b.isBlank()) continue
                if (n.isNotBlank() && n.length !in 2..16) continue
                if (b.length > 120) continue
                if (!db.isDuplicate(target, n, b)) {
                    db.insertImportedRecord(
                        SeedRecord(
                            id = 0,
                            sessionId = target,
                            number = n,
                            barcode = b,
                            time = o.optString("time"),
                            dateGregorian = o.optString("dateGregorian"),
                            dateJalali = o.optString("dateJalali")
                        ), target
                    )
                    imported++
                }
            }
            if (root.has("settings")) {
                val so = root.optJSONObject("settings")
                so?.keys()?.forEach { key ->
                    val v = so.opt(key)
                    if (v != JSONObject.NULL) settingsToWrite[key] = v
                }
            }
            db.writableDatabase.setTransactionSuccessful()
        } finally {
            db.writableDatabase.endTransaction()
        }

        // SharedPreferences cannot participate in SQLite transactions; write them only
        // after the database transaction has committed successfully.
        if (settingsToWrite.isNotEmpty()) {
            settings.replaceFromMap(settingsToWrite)
        }
        return imported
    }
}
