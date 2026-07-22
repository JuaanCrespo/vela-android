package com.vela.android.lab.data.market.source.alpaca

import com.vela.android.lab.BuildConfig

/**
 * Reads Alpaca **test stream** credentials from BuildConfig fields
 * populated at build time from `local.properties` (gitignored).
 *
 * Phase 2.c safety guarantees:
 *
 *  - Keys never appear in source or in version control. They live
 *    only in the developer's local-machine `local.properties`.
 *  - Release builds always have blank values (see
 *    `app/build.gradle.kts → release.buildConfigField`).
 *  - Blank or whitespace-only values resolve to `null`, which the
 *    Phase 2.b client surfaces as
 *    `MarketDataError.AuthenticationFailed` — no WebSocket is opened.
 *  - The credential values are **never** logged. The
 *    [AlpacaCredentials.toString] override redacts the secret and
 *    truncates the key id.
 *
 * The two functional getters are injectable so JVM tests can drive
 * blank-vs-populated behaviour without an Android BuildConfig class.
 */
class BuildConfigAlpacaCredentialsProvider(
    private val keyIdSource: () -> String,
    private val secretSource: () -> String,
) : AlpacaCredentialsProvider {

    override suspend fun read(): AlpacaCredentials? {
        val keyId = keyIdSource().trim()
        val secret = secretSource().trim()
        if (keyId.isEmpty() || secret.isEmpty()) return null
        return AlpacaCredentials(keyId, secret)
    }

    companion object {
        /**
         * Production factory: pulls the BuildConfig fields directly.
         * In a release APK both fields are guaranteed empty by the
         * Gradle configuration.
         */
        fun fromBuildConfig(): BuildConfigAlpacaCredentialsProvider =
            BuildConfigAlpacaCredentialsProvider(
                keyIdSource = { BuildConfig.ALPACA_TEST_KEY_ID },
                secretSource = { BuildConfig.ALPACA_TEST_SECRET },
            )
    }
}
