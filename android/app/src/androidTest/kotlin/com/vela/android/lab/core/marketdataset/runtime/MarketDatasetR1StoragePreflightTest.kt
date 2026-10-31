package com.vela.android.lab.core.marketdataset.runtime

import android.app.Application
import android.os.Bundle
import android.os.Process
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vela.android.lab.BuildConfig
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** ONE storage-only test. No SQLite types, database opening, fixture ingestion or second-stage run. */
@RunWith(AndroidJUnit4::class)
class MarketDatasetR1StoragePreflightTest {
    @Test fun targetCacheOwnershipWrapperPathAndExclusiveCleanup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals(MarketDatasetR1Runner::class.java, instrumentation.javaClass)
        assertEquals(MarketDatasetR1Runner.PREFLIGHT_CLASS, InstrumentationRegistry.getArguments().getString("class"))
        assertEquals(MarketDatasetR1Runner.PREFLIGHT_AUTHORIZATION_VALUE,
            InstrumentationRegistry.getArguments().getString(MarketDatasetR1Runner.AUTHORIZATION_ARGUMENT))
        val target = instrumentation.targetContext
        assertEquals("com.vela.android.lab", target.packageName)
        assertEquals("com.vela.android.lab.test", instrumentation.context.packageName) // Identity only, NEVER its cache.
        assertEquals(target.applicationInfo.uid, Process.myUid())
        assertEquals(Application::class.java, target.applicationContext.javaClass)
        assertFalse(BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED)
        assertEquals("", BuildConfig.ALPACA_TEST_KEY_ID); assertEquals("", BuildConfig.ALPACA_TEST_SECRET)
        val oldPolicy = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeathOnNetwork().build())
        try {
            val owned = MarketDatasetR1Sandbox(target)
            val directory = owned.directory
            val parent = owned.cacheParent
            val database = owned.database
            owned.use {
                owned.verifyOwnership()
                assertTrue(parent.isDirectory && parent.canWrite())
                assertEquals(File(target.dataDir.canonicalFile, "cache"), parent)
                assertEquals(parent, directory.parentFile)
                assertFalse(Files.isSymbolicLink(parent.toPath())); assertFalse(Files.isSymbolicLink(directory.toPath()))
                assertSame(owned.context, owned.context.applicationContext)
                assertEquals(target.packageName, owned.context.applicationContext.packageName)
                assertEquals(File(directory, "vela_market_dataset.db"), owned.context.applicationContext.getDatabasePath("vela_market_dataset.db"))
                assertEquals(directory, database.canonicalFile.parentFile)
                assertFalse(database.exists()) // This test never creates or opens a database.
                for (name in listOf("vela-lab.db", "../vela_market_dataset.db", "/data/data/vela_market_dataset.db")) {
                    try { owned.context.applicationContext.getDatabasePath(name); fail("Forbidden database name accepted") }
                    catch (_: IllegalArgumentException) { /* No normal/external path fallback. */ }
                }
                assertEquals(listOf(".r1-owner"), directory.listFiles()!!.map { it.name })
            }
            assertFalse(directory.exists()); assertFalse(database.exists())
            assertTrue(parent.isDirectory) // Preserve the cache parent and all unrelated cache contents.
            val report = linkedMapOf(
                "target_package" to target.packageName,
                "instrumentation_package" to instrumentation.context.packageName,
                "process_uid" to Process.myUid().toString(),
                "target_uid" to target.applicationInfo.uid.toString(),
                "instrumentation_package_uid" to instrumentation.context.applicationInfo.uid.toString(),
                "cache_parent" to parent.path, "owned_directory" to directory.path, "resolved_database_path" to database.path,
                "application_context_retains_wrapper" to "true", "cleanup_verified" to "true", "sqlite_opened" to "false",
            ).entries.joinToString("\n") { "${it.key}=${it.value}" }
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "\nR1_STORAGE_PREFLIGHT_EVIDENCE_BEGIN\n$report\nR1_STORAGE_PREFLIGHT_EVIDENCE_END\n")
            })
        } finally { StrictMode.setThreadPolicy(oldPolicy) }
        // STOP after this one case. Stage B is a different human authorization and invocation.
    }
}
