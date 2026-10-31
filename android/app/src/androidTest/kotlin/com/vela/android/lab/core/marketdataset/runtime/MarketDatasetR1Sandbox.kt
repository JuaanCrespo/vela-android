package com.vela.android.lab.core.marketdataset.runtime

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import com.vela.android.lab.core.marketdataset.storage.AndroidMarketDatasetDatabase
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Owns one newly created directory, never an arbitrary caller/environment path. */
internal class MarketDatasetR1Sandbox(targetContext: Context) : AutoCloseable {
    private val parent = targetCacheParent(targetContext)
    val owner: String = UUID.randomUUID().toString()
    private val root: File
    private val marker: File
    val database: File
    val context: Context
    val cacheParent: File get() = parent
    val directory: File get() = root
    private var closed = false

    init {
        root = try { Files.createTempDirectory(parent.toPath(), "vela-3a3r1-").toFile().canonicalFile }
        catch (failure: Exception) { throw IllegalStateException(MarketDatasetR1StoragePaths.CACHE_PARENT_UNAVAILABLE, failure) }
        check(root.parentFile == parent && !Files.isSymbolicLink(root.toPath()))
        marker = File(root, ".r1-owner")
        check(marker.createNewFile())
        marker.writeText(owner, Charsets.UTF_8)
        database = File(root, AndroidMarketDatasetDatabase.DATABASE_NAME)
        val targetDatabases = File(targetContext.dataDir, "databases").canonicalFile
        val normalMarket = File(targetDatabases, AndroidMarketDatasetDatabase.DATABASE_NAME).canonicalFile
        val operational = File(targetDatabases, "vela-lab.db").canonicalFile
        // Derive forbidden paths without calling normal getDatabasePath: it may create its parent.
        check(database.canonicalFile != normalMarket && database.canonicalFile != operational)
        check(!root.toPath().startsWith(normalMarket.parentFile!!.toPath()))
        check(!root.toPath().startsWith(operational.parentFile!!.toPath()))
        context = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File {
                check(!closed)
                require(name == AndroidMarketDatasetDatabase.DATABASE_NAME) { "R1 database name rejected" }
                verifyOwnership()
                check(database.canonicalFile.parentFile == root)
                check(!Files.isSymbolicLink(database.toPath()))
                return database
            }
        }
        check(context.applicationContext === context)
        check(context.applicationContext.getDatabasePath(AndroidMarketDatasetDatabase.DATABASE_NAME) == database)
        check(!database.exists()) // Only the new test-owned path is inspected.
    }

    fun verifyOwnership() {
        check(parent.isDirectory && parent.canonicalFile == parent && !Files.isSymbolicLink(parent.toPath()))
        check(root.parentFile == parent && root.name.startsWith("vela-3a3r1-"))
        check(root.canonicalFile == root && !Files.isSymbolicLink(root.toPath()))
        check(marker.canonicalFile.parentFile == root && !Files.isSymbolicLink(marker.toPath()))
        check(marker.isFile && marker.readText(Charsets.UTF_8) == owner)
    }

    override fun close() {
        if (closed) return
        verifyOwnership()
        val allowed = setOf(database.name, "${database.name}-journal", "${database.name}-wal", "${database.name}-shm", marker.name)
        val children = root.listFiles() ?: error("R1 sandbox cannot be enumerated")
        // Validate the entire deletion set before deleting anything. No recursive deletion.
        children.forEach {
            check(it.name in allowed && it.canonicalFile.parentFile == root)
            check(!Files.isSymbolicLink(it.toPath()) && it.isFile)
        }
        children.filter { it != marker }.forEach { Files.delete(it.toPath()) }
        Files.delete(marker.toPath())
        Files.delete(root.toPath())
        closed = true
    }

    companion object {
        private fun targetCacheParent(targetContext: Context): File {
            check(targetContext.packageName == "com.vela.android.lab") { "BLOCKED_3A3R1_TEST_STORAGE_ISOLATION:TARGET_PACKAGE" }
            val info = targetContext.applicationInfo
            check(info.packageName == targetContext.packageName && info.uid == Process.myUid()) {
                "BLOCKED_3A3R1_TEST_STORAGE_ISOLATION:TARGET_UID"
            }
            val data = targetContext.dataDir.canonicalFile
            check(data == File(info.dataDir).canonicalFile) { "BLOCKED_3A3R1_TEST_STORAGE_ISOLATION:TARGET_DATA" }
            val cache = try { targetContext.cacheDir }
            catch (failure: Exception) { throw IllegalStateException(MarketDatasetR1StoragePaths.CACHE_PARENT_UNAVAILABLE, failure) }
            return MarketDatasetR1StoragePaths.establishCacheParent(data, cache)
        }
    }
}
