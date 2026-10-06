package com.srooyesh.seedcounter

/**
 * Keys from the pre-SQLite SharedPreferences formats.
 * These constants exist only for one-time migration compatibility.
 */
@Deprecated("Migration-only compatibility keys. Do not use for new storage.")
object LegacyKeys {
    const val PREFS = "seed_data_v2"
    const val KEY_ACTIVE_VARIETY = "active_variety"
    const val KEY_ACTIVE_DATE = "active_date"
    const val PREFIX = "record|"
}
