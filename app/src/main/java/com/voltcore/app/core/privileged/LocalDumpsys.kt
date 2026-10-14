package com.voltcore.app.core.privileged

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Runs `dumpsys` inside VoltCore's own process — no Shizuku, no other app.
 *
 * Android's battery service answers `dumpsys` for a caller holding both `DUMP` and
 * `PACKAGE_USAGE_STATS` — the permission itself, not only the Usage access switch in
 * settings. Both are signature-level but carry the `development` flag, which means they can
 * be granted to one specific app, once, over USB:
 *
 * ```
 * adb shell pm grant com.voltcore.app android.permission.DUMP
 * adb shell pm grant com.voltcore.app android.permission.PACKAGE_USAGE_STATS
 * ```
 *
 * Unlike Shizuku, which has to be restarted after every reboot, grants made this way stay
 * with the app until it is uninstalled or they are revoked.
 */
class LocalDumpsys(private val context: Context) {

    /** True once both one-time grants have been made. */
    fun isGranted(): Boolean = REQUIRED.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Runs `dumpsys <args>` and hands its output to [consume] line by line, without holding
     * it all in memory. Battery history runs to tens of megabytes, so this matters.
     *
     * @return what [consume] returned, or null when the permission is missing or the system
     *         refused the dump.
     */
    suspend fun <T> stream(args: List<String>, consume: (Sequence<String>) -> T): T? =
        withContext(Dispatchers.IO) {
            if (!isGranted()) return@withContext null
            val process = runCatching {
                ProcessBuilder(listOf(DUMPSYS, "-t", TIMEOUT_SECONDS) + args)
                    .redirectErrorStream(true)
                    .start()
            }.onFailure { Log.w(TAG, "dumpsys $args could not start", it) }
                .getOrNull() ?: return@withContext null

            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    val lines = reader.lineSequence()
                    val iterator = lines.iterator()
                    if (!iterator.hasNext()) {
                        Log.w(TAG, "dumpsys $args printed nothing")
                        return@withContext null
                    }
                    val first = iterator.next()
                    if (first.contains(DENIAL_PREFIX)) {
                        Log.w(TAG, "dumpsys $args refused: $first")
                        return@withContext null
                    }
                    val rest = iterator.asSequence().onEach {
                        if (it.contains(TIMEOUT_MARKER)) Log.w(TAG, "dumpsys $args cut off: $it")
                    }
                    consume(sequenceOf(first) + rest)
                }
            } catch (e: Exception) {
                Log.w(TAG, "dumpsys $args failed", e)
                null
            } finally {
                process.destroy()
            }
        }

    /** Runs a small dump and returns it whole. Only for outputs of a few hundred KB. */
    suspend fun read(args: List<String>): String? =
        stream(args) { lines -> lines.joinToString("\n") }

    private companion object {
        val REQUIRED = listOf(
            "android.permission.DUMP",
            "android.permission.PACKAGE_USAGE_STATS",
        )
        const val DUMPSYS = "/system/bin/dumpsys"
        const val DENIAL_PREFIX = "Permission Denial"
        const val TAG = "VoltCoreDumpsys"
        const val TIMEOUT_SECONDS = "120"
        const val TIMEOUT_MARKER = "DUMP TIMEOUT"
    }
}
