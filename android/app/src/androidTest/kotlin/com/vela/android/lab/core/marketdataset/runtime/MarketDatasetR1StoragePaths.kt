package com.vela.android.lab.core.marketdataset.runtime

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Test-only filesystem guard, also exercised on disposable host fixtures; no Android/SQLite IO. */
internal object MarketDatasetR1StoragePaths {
    const val CACHE_PARENT_UNAVAILABLE = "BLOCKED_3A3R1_CACHE_PARENT_UNAVAILABLE"

    fun establishCacheParent(dataDirectory: File, cacheDirectory: File): File = try {
        val data = dataDirectory.canonicalFile
        check(data.isDirectory) // Never create an arbitrary application data root.
        val expected = File(data, "cache").absoluteFile
        val supplied = cacheDirectory.absoluteFile
        check(supplied.name == "cache" && supplied.parentFile?.canonicalFile == data)
        check(!Files.isSymbolicLink(supplied.toPath()))
        check(supplied.canonicalFile == expected) // Reject aliases into databases/external storage.
        if (!Files.exists(expected.toPath(), NOFOLLOW_LINKS)) {
            try { Files.createDirectory(expected.toPath()) }
            catch (_: FileAlreadyExistsException) { /* Revalidate a concurrent creation below. */ }
        }
        check(!Files.isSymbolicLink(expected.toPath()))
        check(Files.isDirectory(expected.toPath(), NOFOLLOW_LINKS))
        check(expected.canonicalFile == expected && expected.parentFile == data)
        check(Files.isWritable(expected.toPath()) && expected.canWrite())
        expected
    } catch (failure: Exception) {
        throw IllegalStateException(CACHE_PARENT_UNAVAILABLE, failure)
    }
}
