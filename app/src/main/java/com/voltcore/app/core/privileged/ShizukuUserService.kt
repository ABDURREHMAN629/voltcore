package com.voltcore.app.core.privileged

import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

/**
 * The body of the shell-UID helper process.
 *
 * Shizuku launches this class in a separate process running as the `shell` user (UID 2000)
 * — the same identity `adb shell` has. That is enough to force-stop and suspend packages
 * and to write secure settings, none of which an ordinary app may do, and it requires no
 * root: the user authorises Shizuku once through Android's own Wireless debugging.
 *
 * Must keep a no-argument constructor; Shizuku instantiates it reflectively.
 */
class ShizukuUserService : IUserService.Stub {

    @Suppress("unused")
    constructor() : super()

    override fun destroy() {
        exitProcess(0)
    }

    override fun execute(command: Array<String>): String = runCatching {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        process.waitFor()
        output.trim()
    }.getOrElse { "voltcore-error: ${it.message}" }

    override fun executeForExitCode(command: Array<String>): Int = runCatching {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        process.inputStream.use { it.readBytes() }
        process.waitFor()
    }.getOrElse { -1 }
}
