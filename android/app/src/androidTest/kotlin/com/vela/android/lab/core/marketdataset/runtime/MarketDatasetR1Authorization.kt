package com.vela.android.lab.core.marketdataset.runtime

/** Disjoint human checkpoints, never an automatic workflow. No IO or test discovery. */
internal object MarketDatasetR1Authorization {
    const val PREFLIGHT_CLASS = "com.vela.android.lab.core.marketdataset.runtime.MarketDatasetR1StoragePreflightTest"
    const val SQLITE_CLASS = "com.vela.android.lab.core.marketdataset.runtime.MarketDatasetAndroidRuntimeIntegrityTest"
    const val SMOKE_CLASS = "com.vela.android.lab.core.marketdataset.runtime.MarketDatasetR1SqliteSmokeTest"
    const val SMOKE_SELECTOR = "$SMOKE_CLASS#openV1SchemaAndRequiredConfiguration"
    const val ARGUMENT = "vela3a3r1Authorization"
    const val PREFLIGHT_VALUE = "STORAGE_PREFLIGHT_EXPLICITLY_AUTHORIZED"
    const val SQLITE_VALUE = "SQLITE_13_EXPLICITLY_AUTHORIZED"
    const val SMOKE_VALUE = "SQLITE_SMOKE_EXPLICITLY_AUTHORIZED"

    fun validate(arguments: Map<String, String?>) {
        require(arguments.keys == setOf("class", ARGUMENT)) { "R1 requires exactly one class and its distinct authorization" }
        val expected = when (arguments["class"]) {
            PREFLIGHT_CLASS -> PREFLIGHT_VALUE
            SQLITE_CLASS -> SQLITE_VALUE
            SMOKE_SELECTOR -> SMOKE_VALUE
            else -> throw IllegalArgumentException("R1 rejects broad, combined, unapproved method or unrelated selection")
        }
        require(arguments[ARGUMENT] == expected) { "R1 checkpoint authorization does not match the selected stage" }
    }
}
