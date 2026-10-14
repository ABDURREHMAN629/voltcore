package com.voltcore.app.core.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Borrows shell-UID authority from Shizuku.
 *
 * Shizuku runs a small service that the user starts once, either from a PC over ADB or —
 * on Android 11 and newer — entirely on the phone through Wireless debugging. Apps that
 * the user authorises can then ask it to run code as UID 2000, the same identity
 * `adb shell` has. Crucially this is not root: nothing in the system partition changes,
 * the bootloader stays locked, and Play Integrity is unaffected, so banking apps keep
 * working. The trade-off is that Shizuku has to be restarted after every reboot.
 */
class ShizukuBackend(private val context: Context) : PrivilegedBackend {

    override val tier = PrivilegeTier.SHIZUKU
    override val supportsPersistentFreeze = true

    @Volatile
    private var service: IUserService? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuUserService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("volt")
        .debuggable(false)
        .version(SERVICE_VERSION)

    /** True when the Shizuku service is running, regardless of whether we may use it. */
    fun isServiceRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.isPreV11()) return false
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** True when the user has denied us and ticked "don't ask again". */
    fun isPermanentlyDenied(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED &&
            !Shizuku.shouldShowRequestPermissionRationale()
    }.getOrDefault(false)

    override suspend fun isAvailable(): Boolean = isServiceRunning() && hasPermission()

    /**
     * Asks Shizuku for permission. The prompt is shown by the Shizuku app itself, so this
     * can be called without an Activity; the answer arrives on the listener.
     */
    suspend fun requestPermission(): Boolean {
        if (!isServiceRunning()) return false
        if (hasPermission()) return true

        val deferred = CompletableDeferred<Boolean>()
        val listener = Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == PERMISSION_REQUEST_CODE) {
                deferred.complete(result == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        return try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { deferred.await() } ?: false
        } catch (e: Exception) {
            false
        } finally {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    override suspend fun connect(): Boolean {
        service?.let { if (it.asBinder().isBinderAlive) return true }
        if (!isAvailable()) return false

        val deferred = CompletableDeferred<IUserService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val bound = if (binder != null && binder.pingBinder()) {
                    IUserService.Stub.asInterface(binder)
                } else {
                    null
                }
                service = bound
                deferred.complete(bound)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }

        return try {
            Shizuku.bindUserService(userServiceArgs, connection)
            withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() } != null
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun forceStop(packageName: String): PrivilegeResult =
        runShell("am force-stop --user 0 $packageName", packageName)

    override suspend fun setSuspended(packageName: String, suspended: Boolean): PrivilegeResult {
        val verb = if (suspended) "suspend" else "unsuspend"
        val result = runShell("pm $verb --user 0 $packageName", packageName)
        if (result is PrivilegeResult.Success && suspended) {
            runShell("am force-stop --user 0 $packageName", packageName)
        }
        return result
    }

    override suspend fun setEnabled(packageName: String, enabled: Boolean): PrivilegeResult {
        val verb = if (enabled) "enable" else "disable-user"
        return runShell("pm $verb --user 0 $packageName", packageName)
    }

    override suspend fun setStandbyBucket(packageName: String, bucket: String): PrivilegeResult =
        runShell("am set-standby-bucket $packageName $bucket", packageName)

    override suspend fun putGlobalSetting(key: String, value: String): PrivilegeResult {
        if (!SAFE_TOKEN.matches(key) || !SAFE_TOKEN.matches(value)) {
            return PrivilegeResult.Failed("Rejected unsafe setting")
        }
        return exec("settings put global $key $value")
    }

    /**
     * Runs a read-only diagnostic command and returns its output.
     *
     * Exposed separately from the mutating operations because the callers are different
     * in kind: this one is for reading the system's own battery accounting out of
     * `dumpsys`, which no ordinary app may do but shell may.
     */
    suspend fun readCommand(command: String): String? = execForOutput(command)

    /** Reads a package's current state, used to show whether a freeze actually held. */
    suspend fun isSuspended(packageName: String): Boolean? {
        if (!isValidPackage(packageName)) return null
        val out = execForOutput("pm dump $packageName | grep -m1 suspended=") ?: return null
        return when {
            out.contains("suspended=true") -> true
            out.contains("suspended=false") -> false
            else -> null
        }
    }

    private suspend fun runShell(command: String, packageName: String): PrivilegeResult {
        if (!isValidPackage(packageName)) {
            return PrivilegeResult.Failed("Invalid package name")
        }
        return exec(command)
    }

    private suspend fun exec(command: String): PrivilegeResult = withContext(Dispatchers.IO) {
        if (!connect()) {
            return@withContext PrivilegeResult.Unsupported("Shizuku is not connected")
        }
        val svc = service ?: return@withContext PrivilegeResult.Unsupported("No Shizuku service")

        runCatching {
            val code = svc.executeForExitCode(arrayOf("sh", "-c", command))
            if (code == 0) {
                PrivilegeResult.Success
            } else {
                PrivilegeResult.Failed("Command exited with $code")
            }
        }.getOrElse { PrivilegeResult.Failed(it.message ?: "Shizuku call failed") }
    }

    private suspend fun execForOutput(command: String): String? = withContext(Dispatchers.IO) {
        if (!connect()) return@withContext null
        val svc = service ?: return@withContext null
        runCatching { svc.execute(arrayOf("sh", "-c", command)) }.getOrNull()
    }

    private companion object {
        const val SERVICE_VERSION = 1
        const val PERMISSION_REQUEST_CODE = 5001
        const val PERMISSION_TIMEOUT_MS = 60_000L
        const val BIND_TIMEOUT_MS = 12_000L

        /**
         * Package names and setting keys are interpolated into a shell command, so they
         * are whitelisted rather than escaped. Nothing outside these characters is legal
         * in an Android package name anyway.
         */
        val SAFE_TOKEN = Regex("[A-Za-z0-9._:@/-]{1,255}")

        fun isValidPackage(name: String): Boolean =
            name.isNotBlank() && SAFE_TOKEN.matches(name) && name.contains('.')
    }
}
