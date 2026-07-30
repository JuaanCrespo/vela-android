package com.vela.android.lab.safety

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafeApkProvenanceScriptTest {

    @Test
    fun `safe APK verifier passes its synthetic evidence matrix`() {
        val testScript = locateVerifierTests()
        val windowsRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
        val powershell = File(
            windowsRoot,
            "System32/WindowsPowerShell/v1.0/powershell.exe",
        )
        assertTrue(powershell.isFile, "Windows PowerShell 5.1 is required")

        val process = ProcessBuilder(
            powershell.absolutePath,
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            testScript.absolutePath,
        ).redirectErrorStream(true).start()

        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(finished, "Verifier self-tests timed out")
        assertEquals(0, process.exitValue(), output.take(2_000))
        assertTrue(
            output.lineSequence().any {
                it.trim() == "VERIFY_SAFE_APK_SELF_TEST_PASS tests=13"
            },
            output.take(2_000),
        )
    }

    private fun locateVerifierTests(): File {
        val relativePath = "scripts/tests/Verify-SafeApk.Tests.ps1"
        val candidates = listOf(
            File("../$relativePath"),
            File(relativePath),
            File("android/$relativePath"),
            File("../../android/$relativePath"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Cannot locate $relativePath from ${File(".").absolutePath}")
    }
}
