package com.dboycht.keyforge.probe

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Outcome of a single probe step. */
internal enum class ProbeStatus {
    PASS,
    FAIL,
    WARN,
    INFO,
}

/** One row of the probe report. */
internal data class ProbeCheck(
    val title: String,
    val status: ProbeStatus,
    val detail: String,
)

/** The full report plus a one-line verdict to show at the top of the screen. */
internal data class ProbeReport(
    val checks: List<ProbeCheck>,
    val verdict: String,
    val fatal: Boolean,
)

/**
 * Answers one question: **can this phone register itself as a Bluetooth HID
 * keyboard at all?**
 *
 * Every step is recorded instead of short-circuiting, because when the answer
 * is "no" the interesting part is *where* it broke: missing permission, no
 * Bluetooth, or a vendor build that does not expose the HID device profile.
 *
 * The probe deliberately talks to the real platform APIs rather than merely
 * checking `Build.VERSION` — a device can advertise Android 13+ and still have
 * the profile stripped out by the vendor.
 */
internal object BluetoothHidProbe {

    private const val PROXY_TIMEOUT_MS = 5_000L

    /**
     * How long to wait for `onAppStatusChanged` after calling `registerApp`.
     * Measured on OPPO K12 Plus / Android 16: about 100 ms. 2 s leaves room for a
     * slow stack without making the probe feel stuck.
     */
    private const val CALLBACK_WAIT_MS = 2_000L

    /**
     * Runs every check and returns the report.
     *
     * [onProxyObtained] receives the HID profile proxy once it is connected, so the
     * caller can later unregister the app: the platform allows only ONE registered
     * HID app at a time, and a screen that keeps the registration blocks every
     * other screen (including the keyboard session).
     */
    fun run(context: Context, onProxyObtained: (BluetoothHidDevice) -> Unit = {}): ProbeReport {
        val checks = mutableListOf<ProbeCheck>()

        checks += ProbeCheck(
            title = "Build",
            status = ProbeStatus.INFO,
            detail = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.DISPLAY}",
        )

        return runCatching { probeBluetooth(context, checks, onProxyObtained) }
            .getOrElse { error ->
                // The full stack is the most useful thing on screen here: release
                // builds strip android.util.Log via R8, so the on-screen text (and
                // the "copy all results" export) is the reliable diagnostic channel
                // on a real device.
                val stack = error.stackTraceToString()
                    .lineSequence()
                    .take(12)
                    .joinToString("\n")
                checks += ProbeCheck(
                    title = "Probe crashed",
                    status = ProbeStatus.FAIL,
                    detail = "${error.javaClass.simpleName}: ${error.message}\n$stack",
                )
                ProbeReport(
                    checks = checks,
                    verdict = "探针运行中抛出异常，见上方最后一条。",
                    fatal = true,
                )
            }
    }

    private fun probeBluetooth(
        context: Context,
        checks: MutableList<ProbeCheck>,
        onProxyObtained: (BluetoothHidDevice) -> Unit,
    ): ProbeReport {
        // --- 1. Does this build even have the API? -------------------------
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            checks += ProbeCheck(
                title = "API level",
                status = ProbeStatus.FAIL,
                detail = "BluetoothHidDevice needs API 28+; this device is API ${Build.VERSION.SDK_INT}.",
            )
            return ProbeReport(checks, "系统版本过低，无法作为蓝牙键盘。", fatal = true)
        }
        checks += ProbeCheck(
            title = "API level",
            status = ProbeStatus.PASS,
            detail = "API ${Build.VERSION.SDK_INT} ≥ 28 (BluetoothHidDevice available).",
        )

        // --- 2. Bluetooth adapter ------------------------------------------
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) {
            checks += ProbeCheck(
                title = "Bluetooth adapter",
                status = ProbeStatus.FAIL,
                detail = "No BluetoothManager/BluetoothAdapter on this device.",
            )
            return ProbeReport(checks, "本机没有蓝牙适配器。", fatal = true)
        }
        checks += ProbeCheck(
            title = "Bluetooth adapter",
            status = ProbeStatus.PASS,
            detail = "Present. (Reading the local address needs BLUETOOTH_CONNECT, checked next.)",
        )

        // --- 3. Runtime permissions -----------------------------------------
        // Checked before touching any adapter member: on API 31+ a missing
        // BLUETOOTH_CONNECT makes even `adapter.address` throw SecurityException
        // (observed on OPPO K12 Plus / Android 16), which used to abort the probe
        // here instead of reporting the actionable "grant the permission" verdict.
        val grantedConnect = isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)
        checks += ProbeCheck(
            title = "Permission BLUETOOTH_CONNECT",
            status = if (grantedConnect) ProbeStatus.PASS else ProbeStatus.FAIL,
            detail = if (grantedConnect) {
                "Granted."
            } else {
                "Not granted — registerApp/getProfileProxy will fail until it is."
            },
        )
        val grantedScan = isGranted(context, Manifest.permission.BLUETOOTH_SCAN)
        checks += ProbeCheck(
            title = "Permission BLUETOOTH_SCAN",
            status = if (grantedScan) ProbeStatus.PASS else ProbeStatus.WARN,
            detail = if (grantedScan) "Granted." else "Not granted (only needed to discover hosts).",
        )
        if (!grantedConnect) {
            return ProbeReport(checks, "缺少蓝牙权限，请先在应用内授权后重新检测。", fatal = true)
        }

        // --- 4. Adapter identity (safe now that BLUETOOTH_CONNECT is present) --
        val address = runCatching { adapter.address }.getOrNull()
        checks += ProbeCheck(
            title = "Bluetooth address",
            status = if (address != null) ProbeStatus.PASS else ProbeStatus.WARN,
            detail = address ?: "Address unavailable (stack returned null / hidden).",
        )

        // --- 5. Is Bluetooth switched on? ----------------------------------
        val enabled = runCatching { adapter.isEnabled }.getOrDefault(false)
        checks += ProbeCheck(
            title = "Bluetooth enabled",
            status = if (enabled) ProbeStatus.PASS else ProbeStatus.FAIL,
            detail = if (enabled) "On." else "Off — please enable Bluetooth and re-run.",
        )
        if (!enabled) {
            return ProbeReport(checks, "蓝牙未开启。", fatal = true)
        }

        // --- 6. The decisive test: HID device profile proxy ------------------
        val proxyHolder = AtomicReference<BluetoothHidDevice?>(null)
        val latch = CountDownLatch(1)
        val proxyListener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                Log.i(ProbeConstants.TAG, "onServiceConnected profile=$profile proxy=$proxy")
                proxyHolder.set(proxy as? BluetoothHidDevice)
                latch.countDown()
            }

            override fun onServiceDisconnected(profile: Int) {
                Log.w(ProbeConstants.TAG, "onServiceDisconnected profile=$profile")
                proxyHolder.set(null)
                latch.countDown()
            }
        }

        val requested = runCatching {
            adapter.getProfileProxy(context, proxyListener, BluetoothProfile.HID_DEVICE)
        }.getOrElse { error ->
            checks += ProbeCheck(
                title = "getProfileProxy(HID_DEVICE)",
                status = ProbeStatus.FAIL,
                detail = "Threw ${error.javaClass.simpleName}: ${error.message}",
            )
            false
        }

        if (!requested) {
            checks += ProbeCheck(
                title = "getProfileProxy(HID_DEVICE)",
                status = ProbeStatus.FAIL,
                detail = "Returned false — this build does not expose the HID device profile.",
            )
            return ProbeReport(
                checks = checks,
                verdict = "❌ 本机蓝牙协议栈未开放 HID Device profile（第一步就失败）。",
                fatal = true,
            )
        }

        val arrived = latch.await(PROXY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val proxy = proxyHolder.get()
        checks += ProbeCheck(
            title = "getProfileProxy(HID_DEVICE)",
            status = if (proxy != null) ProbeStatus.PASS else ProbeStatus.FAIL,
            detail = when {
                proxy != null -> "Proxy connected after ≤ ${PROXY_TIMEOUT_MS} ms."
                !arrived -> "Timed out after ${PROXY_TIMEOUT_MS} ms with no callback."
                else -> "Callback fired but the proxy was null."
            },
        )
        if (proxy == null) {
            return ProbeReport(
                checks = checks,
                verdict = "❌ 拿不到 HID Device 代理，无法作为蓝牙键盘。",
                fatal = true,
            )
        }
        // Hand the proxy to the caller so it can unregister later (single global slot).
        runCatching { onProxyObtained(proxy) }

        // --- 6. registerApp: the call the real app depends on ---------------
        //
        // If a keyboard session already holds the registration (the foreground
        // service, which owns it), do NOT register again: the platform allows one
        // registered HID app at a time, so a second attempt fails and, worse, can
        // knock the live session out. Report the live session instead.
        val liveSession = com.dboycht.keyforge.session.HidSessionManager.peek()
        if (liveSession != null &&
            liveSession.state.value.phase == com.dboycht.keyforge.session.SessionPhase.Registered
        ) {
            checks += ProbeCheck(
                title = "registerApp",
                status = ProbeStatus.PASS,
                detail = "Already registered by the running keyboard session " +
                    "(connected hosts: ${liveSession.state.value.connectedDevices.ifEmpty { listOf("none") }.joinToString()}). " +
                    "Probe did not register a second time on purpose.",
            )
            checks += ProbeCheck(
                title = "HID callback events",
                status = ProbeStatus.INFO,
                detail = "Not re-tested here: the live session owns the callback. See the keyboard screen's event log.",
            )
            return ProbeReport(
                checks = checks,
                verdict = "✅ 本机支持蓝牙 HID 键盘（键盘会话已注册，无需重复检测）。",
                fatal = false,
            )
        }

        val executor = Executors.newSingleThreadExecutor()
        val callbackEvents = mutableListOf<String>()
        // The synchronous return value of registerApp is NOT the verdict: on
        // Android 13+ it can return false while the registration actually succeeds
        // (observed on OPPO K12 Plus / Android 16: return=false but
        // onAppStatusChanged(registered=true) arrived ~100 ms later). Treating the
        // return value as truth produced a false "❌ registerApp 失败" verdict.
        val registeredByCallback = java.util.concurrent.atomic.AtomicBoolean(false)
        val unregisteredByCallback = java.util.concurrent.atomic.AtomicBoolean(false)
        val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                val line = "onAppStatusChanged(registered=$registered, device=${pluggedDevice?.address})"
                Log.i(ProbeConstants.TAG, line)
                if (registered) registeredByCallback.set(true) else unregisteredByCallback.set(true)
                synchronized(callbackEvents) { callbackEvents += line }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                val line = "onConnectionStateChanged(state=${stateName(state)}, device=${device?.address})"
                Log.i(ProbeConstants.TAG, line)
                synchronized(callbackEvents) { callbackEvents += line }
            }
        }

        val settings = BluetoothHidDeviceAppSdpSettings(
            ProbeConstants.SDP_NAME,
            ProbeConstants.SDP_DESCRIPTION,
            ProbeConstants.SDP_PROVIDER,
            ProbeConstants.SDP_SUBCLASS_KEYBOARD,
            ProbeConstants.KEYBOARD_REPORT_DESCRIPTOR,
        )

        val usedApi = if (Build.VERSION.SDK_INT >= 34) {
            "registerApp(settings, qosIn, qosOut, executor, callback) [API 34+]"
        } else {
            // API 28-33: both QoS arguments are still required in some platform
            // builds, so pass null explicitly rather than relying on a default.
            "registerApp(settings, qosIn, qosOut, executor, callback) [API 28-33]"
        }
        val result = runCatching { proxy.registerApp(settings, null, null, executor, callback) }
        val returnedTrue = result.getOrDefault(false)
        val thrown = result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }

        // Wait for the authoritative signal. The loop is written so that a callback
        // that arrived BEFORE this point is still honoured: an earlier
        // registration (from a previous probe run in the same process) can fire
        // onAppStatusChanged before our own registerApp call returns, and a naive
        // "sleep then read" would then report a false failure.
        val deadline = System.currentTimeMillis() + CALLBACK_WAIT_MS
        while (!registeredByCallback.get() && !unregisteredByCallback.get() &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(50)
        }
        // The verdict is "a registration was live at some point during this run":
        // that is exactly the capability the probe exists to test. A later
        // unregister (including one caused by re-calling registerApp while already
        // registered) must not turn a demonstrated capability into a failure.
        val registered = registeredByCallback.get()

        checks += ProbeCheck(
            title = "registerApp",
            status = if (registered) ProbeStatus.PASS else ProbeStatus.FAIL,
            detail = buildString {
                append(usedApi)
                append(" · return=")
                append(returnedTrue)
                append(" · callback registered=")
                append(registered)
                thrown?.let { append(" · threw $it") }
            },
        )

        val events = synchronized(callbackEvents) { callbackEvents.toList() }
        checks += ProbeCheck(
            title = "HID callback events",
            status = if (events.isEmpty()) ProbeStatus.WARN else ProbeStatus.PASS,
            detail = events.joinToString(" | ").ifEmpty {
                "No callback within $CALLBACK_WAIT_MS ms (some builds only call back after pairing)."
            },
        )

        // --- 7. Report what a real session would need next -------------------
        // sendReport cannot be exercised yet: it needs a BluetoothDevice that
        // the stack has actually connected (BluetoothDevice has no public
        // constructor), i.e. a paired host. That is the next milestone, not
        // something this probe can fake.
        checks += ProbeCheck(
            title = "sendReport",
            status = ProbeStatus.INFO,
            detail = "Not exercised: needs a connected host device. " +
                "Pair the phone with the iPad/PC, then the keyboard session can be tested.",
        )

        val verdict = when {
            registered ->
                "✅ 本机支持蓝牙 HID 键盘（registerApp 成功）。可以按计划开发。"
            else ->
                "❌ HID Device profile 存在但注册为键盘应用失败：${thrown ?: "回调未报 registered=true"}。"
        }

        return ProbeReport(checks = checks, verdict = verdict, fatal = !registered)
    }

    private fun stateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "UNKNOWN($state)"
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
