package com.dboycht.keyforge.session

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
import com.dboycht.keyforge.hid.HidReport
import com.dboycht.keyforge.hid.KeyboardState
import com.dboycht.keyforge.probe.ProbeConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Everything the UI needs to know about the HID session, as one snapshot. */
internal data class SessionUiState(
    val phase: SessionPhase = SessionPhase.Idle,
    val registered: Boolean = false,
    val connectedDevices: List<String> = emptyList(),
    val pairedDevices: List<PairedDevice> = emptyList(),
    val log: List<String> = emptyList(),
)

/** A bonded device the user can ask to connect to. */
internal data class PairedDevice(
    val name: String,
    val address: String,
    val device: BluetoothDevice,
)

/** Coarse session phases; the UI renders the first, the log carries the detail. */
internal enum class SessionPhase {
    Idle,
    PermissionMissing,
    BluetoothOff,
    ProfileConnecting,
    ProfileUnavailable,
    Registering,
    Registered,
    Failed,
}

/** Calls that could not be executed, with the reason preserved for the UI. */
internal sealed interface SessionResult {
    data class Ok(val detail: String) : SessionResult
    data class Rejected(val reason: String) : SessionResult
}

/**
 * Owns the `BluetoothHidDevice` session: profile proxy, app registration, peer
 * connections and report sending.
 *
 * Design notes:
 * - every step reports through [state] so the UI never has to guess, and through
 *   [events] so a real session leaves a trail that can be read back from a
 *   screenshot or `adb logcat` when something misbehaves;
 * - reports go through one [KeyboardState] (so a down/up pair can never disagree
 *   about what is held) and one [ReportThrottle] (so pairs stay ordered);
 * - Bluetooth types are only touched from callbacks and from [start], never from
 *   the caller's thread.
 */
internal class HidSession(
    private val context: Context,
    private val clock: SessionClock,
    private val onEvent: (String) -> Unit,
) {
    private val tag = ProbeConstants.TAG

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private val executor: Executor = Executors.newSingleThreadExecutor()
    private val keyboard = KeyboardState()
    private val throttle = ReportThrottle(clock)
    private val events = ArrayDeque<String>()

    /**
     * Serialises everything that touches [keyboard] / [throttle] / [events].
     *
     * Why this exists: the UI sends keys from coroutines, and auto-repeat fires
     * them in rapid succession. Without a lock several threads mutated the keyboard
     * state and the throttle at once, which crashed the process
     * (`NegativeArraySizeException` inside the Compose snapshot machinery - see
     * `ERROR.md` E9). One lock, held for short non-blocking sections, is enough: a
     * report is built and handed to the stack, nothing slow happens inside.
     */
    private val reportLock = Any()

    /** Pending auto-repeat burst: usage being repeated, its key code and the count. */
    private var burstUsage: Int? = null
    private var burstKeyCode: Int = 0
    private var burstCount: Int = 0
    private var burstFlush: Cancellable? = null

    private var adapter: BluetoothAdapter? = null
    private var proxy: BluetoothHidDevice? = null
    private var registered = false

    /** Guards the "unregister then register again" self-healing loop. */
    private var registerAttempts = 0

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            if (isRegistered) registerAttempts = 0
            event("onAppStatusChanged(registered=$isRegistered, device=${pluggedDevice?.address ?: "-"})")
            publish(phase = if (isRegistered) SessionPhase.Registered else SessionPhase.Registering)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            val name = device?.let { safeName(it) } ?: "-"
            event("onConnectionStateChanged(${stateName(state)}, device=$name)")
            if (state == BluetoothProfile.STATE_CONNECTED && device != null) {
                rememberConnected(device)
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && device != null) {
                forgetConnected(device)
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            event("onGetReport(type=$type, id=$id, size=$bufferSize) - not implemented")
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            event("onSetReport(type=$type, id=$id, bytes=${data?.size ?: 0}) - ignored")
        }

        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
            event("onSetProtocol($protocol)")
        }

        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
            event("onInterruptData(id=$reportId, bytes=${data?.size ?: 0}) - ignored")
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            event("onVirtualCableUnplug(device=${device?.address ?: "-"})")
        }
    }

    /** Re-runs registration from the UI ("refresh session") without a new proxy. */
    fun refresh() {
        if (proxy == null) {
            start()
        } else {
            registerAttempts = 0
            register()
        }
    }

    /** Connects the profile proxy and registers the app; safe to call repeatedly. */
    fun start() {
        if (!hasConnectPermission()) {
            event("BLUETOOTH_CONNECT not granted - cannot start")
            publish(phase = SessionPhase.PermissionMissing)
            return
        }

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val localAdapter = manager?.adapter
        adapter = localAdapter
        if (localAdapter == null) {
            event("no BluetoothAdapter on this device")
            publish(phase = SessionPhase.Failed)
            return
        }
        if (!runCatching { localAdapter.isEnabled }.getOrDefault(false)) {
            event("Bluetooth is off")
            publish(phase = SessionPhase.BluetoothOff, paired = emptyList())
            return
        }

        publish(paired = bondedDevices(localAdapter))

        if (proxy != null) {
            // Already have the proxy: re-registering would fail with "already registered".
            event("profile proxy already present; skipping getProfileProxy")
            if (!registered) register()
            return
        }

        publish(phase = SessionPhase.ProfileConnecting)
        event("getProfileProxy(HID_DEVICE) ...")
        val requested = runCatching {
            localAdapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        }.getOrElse { error ->
            event("getProfileProxy threw ${error.javaClass.simpleName}: ${error.message}")
            false
        }
        if (!requested) {
            event("getProfileProxy returned false - HID device profile not exposed by this build")
            publish(phase = SessionPhase.ProfileUnavailable)
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, service: BluetoothProfile?) {
            event("onServiceConnected(profile=$profile)")
            val hid = service as? BluetoothHidDevice
            proxy = hid
            if (hid == null) {
                event("proxy is not a BluetoothHidDevice")
                publish(phase = SessionPhase.ProfileUnavailable)
                return
            }
            register()
        }

        override fun onServiceDisconnected(profile: Int) {
            event("onServiceDisconnected(profile=$profile)")
            proxy = null
            registered = false
            publish(phase = SessionPhase.Idle)
        }
    }

    private fun register() {
        val hid = proxy ?: return
        publish(phase = SessionPhase.Registering)
        val settings = BluetoothHidDeviceAppSdpSettings(
            ProbeConstants.SDP_NAME,
            ProbeConstants.SDP_DESCRIPTION,
            ProbeConstants.SDP_PROVIDER,
            ProbeConstants.SDP_SUBCLASS_KEYBOARD,
            ProbeConstants.KEYBOARD_REPORT_DESCRIPTOR,
        )
        val result = runCatching { hid.registerApp(settings, null, null, executor, callback) }
        val returned = result.getOrDefault(false)
        // AUTHORITATIVE SIGNAL IS THE CALLBACK, NOT THE RETURN VALUE.
        // Measured on OPPO K12 Plus / Android 16: registerApp returns false and then
        // onAppStatusChanged(registered = true) arrives ~100 ms later. Keying off the
        // return value marks a working session as failed and hides the connect UI.
        event("registerApp returned $returned (verdict comes from onAppStatusChanged)")
        if (result.isFailure) {
            event("registerApp threw ${result.exceptionOrNull()?.javaClass?.simpleName}: " +
                "${result.exceptionOrNull()?.message}")
        }
        // Self-healing, deliberately delayed: the callback may simply not have
        // arrived yet, so a false return value is NOT proof of failure. If nothing
        // registered after a grace period, a previous registration (another screen,
        // or an earlier run whose process died) is still holding the single slot:
        // clear it and try again.
        clock.schedule(REGISTER_CONFIRM_DELAY_MS) {
            if (!registered && registerAttempts < MAX_REGISTER_ATTEMPTS) {
                registerAttempts++
                event("still not registered after ${REGISTER_CONFIRM_DELAY_MS} ms; unregistering and retrying " +
                    "($registerAttempts/$MAX_REGISTER_ATTEMPTS)")
                runCatching { hid.unregisterApp() }
                clock.schedule(REGISTER_RETRY_DELAY_MS) { register() }
            }
        }
    }

    /** Asks the stack to connect the HID channel to a bonded host. */
    fun connect(device: BluetoothDevice): SessionResult {
        val hid = proxy
        if (hid == null) return SessionResult.Rejected("profile proxy not ready")
        if (!registered) return SessionResult.Rejected("app not registered yet")
        if (!hasConnectPermission()) return SessionResult.Rejected("BLUETOOTH_CONNECT not granted")
        val ok = runCatching { hid.connect(device) }.getOrElse { error ->
            event("connect() threw ${error.javaClass.simpleName}: ${error.message}")
            false
        }
        event("connect(${safeName(device)}) -> $ok")
        return if (ok) SessionResult.Ok("connect requested") else SessionResult.Rejected("stack rejected connect")
    }

    fun disconnect(device: BluetoothDevice): SessionResult {
        val hid = proxy ?: return SessionResult.Rejected("profile proxy not ready")
        val ok = runCatching { hid.disconnect(device) }.getOrDefault(false)
        event("disconnect(${safeName(device)}) -> $ok")
        return if (ok) SessionResult.Ok("disconnect requested") else SessionResult.Rejected("stack rejected disconnect")
    }

    /**
     * Taps a key: down report, throttle gap, up report - nothing left held.
     *
     * This is what a soft keyboard needs (one tap = one keystroke). It deliberately
     * releases only this key, so a latched modifier (Shift) stays on for the next
     * tap: "tap Shift, tap a" produces a capital A.
     */
    fun tapKey(keyCode: Int, usage: Int): SessionResult {
        val hid = proxy ?: return SessionResult.Rejected("profile proxy not ready")
        val targets = deviceTargets()
        if (targets.isEmpty()) return SessionResult.Rejected("no host connected - press Connect first")

        val down: Boolean
        synchronized(reportLock) {
            val press = keyboard.press(usage)
            if (press.dropped) {
                event("tap(0x%02X) dropped: %s".format(usage, press.reason))
                return SessionResult.Rejected(press.reason ?: "report full")
            }
            down = targets.all { send(hid, it, press.report) }
        }
        // The throttle gap is deliberately OUTSIDE the lock: it is the point of the
        // gap that the up report lands later, and sleeping while holding the lock
        // would block other keys for no reason.
        awaitThrottleSlot()
        val up: Boolean
        synchronized(reportLock) {
            up = targets.all { send(hid, it, keyboard.release(usage).report) }
        }
        if (!down || !up) return SessionResult.Rejected("sendReport failed (see log)")
        noteBurst(keyCode, usage)
        return SessionResult.Ok("tapped 0x%02X".format(usage))
    }

    /**
     * Types one key: sends the down report, waits out the throttle gap, then the
     * up report.
     *
     * Kept as the "hold" primitive used by tests and by single-shot callers; soft
     * keyboards should use [tapKey], which preserves latched modifiers.
     *
     * [keyCode] is only used for the log line; [usage] is what goes on the wire.
     */
    fun sendKey(keyCode: Int, usage: Int): SessionResult {
        val hid = proxy ?: return SessionResult.Rejected("profile proxy not ready")
        val targets = deviceTargets()
        if (targets.isEmpty()) {
            return SessionResult.Rejected("no host connected - press Connect first")
        }
        val press = keyboard.press(usage)
        if (press.dropped) {
            event("press(0x%02X) dropped: %s".format(usage, press.reason))
            return SessionResult.Rejected(press.reason ?: "report full")
        }
        val down = targets.all { send(hid, it, press.report) }
        awaitThrottleSlot()
        val up = targets.all { send(hid, it, keyboard.release(usage).report) }
        if (!down || !up) return SessionResult.Rejected("sendReport failed (see log)")
        event("tap ${keyName(keyCode)} usage=0x%02X".format(usage))
        return SessionResult.Ok("sent 0x%02X".format(usage))
    }

    /**
     * Presses a key and leaves it held; pair with [releaseKey].
     *
     * A real keyboard layout needs this: holding Shift while pressing `a` is one
     * gesture with two fingers, so the UI must be able to commit the modifier
     * first and keep it down across other keys.
     */
    fun pressKey(keyCode: Int, usage: Int): SessionResult {
        val hid = proxy ?: return SessionResult.Rejected("profile proxy not ready")
        val targets = deviceTargets()
        if (targets.isEmpty()) return SessionResult.Rejected("no host connected - press Connect first")

        val ok: Boolean
        synchronized(reportLock) {
            val press = keyboard.press(usage)
            if (press.dropped) {
                event("press(0x%02X) dropped: %s".format(usage, press.reason))
                return SessionResult.Rejected(press.reason ?: "report full")
            }
            ok = targets.all { send(hid, it, press.report) }
        }
        if (!ok) return SessionResult.Rejected("sendReport failed (see log)")
        event("down ${keyName(keyCode)} usage=0x%02X".format(usage))
        return SessionResult.Ok("pressed 0x%02X".format(usage))
    }

    /** Releases a key previously pressed with [pressKey]. */
    fun releaseKey(keyCode: Int, usage: Int): SessionResult {
        val hid = proxy ?: return SessionResult.Rejected("profile proxy not ready")
        val targets = deviceTargets()
        if (targets.isEmpty()) return SessionResult.Rejected("no host connected")

        val ok: Boolean
        synchronized(reportLock) {
            val released = keyboard.release(usage)
            ok = targets.all { send(hid, it, released.report) }
        }
        if (!ok) return SessionResult.Rejected("sendReport failed (see log)")
        event("up   ${keyName(keyCode)} usage=0x%02X".format(usage))
        return SessionResult.Ok("released 0x%02X".format(usage))
    }

    /** Sends the "all keys up" report to every connected host: clears stuck keys. */
    fun releaseAll(): SessionResult {
        val hid = proxy ?: return SessionResult.Rejected("profile proxy not ready")
        val targets = deviceTargets()
        if (targets.isEmpty()) return SessionResult.Rejected("no host connected")
        val ok: Boolean
        synchronized(reportLock) {
            val report = keyboard.releaseAll()
            ok = targets.all { send(hid, it, report) }
        }
        event("releaseAll -> $ok")
        return if (ok) SessionResult.Ok("all keys released") else SessionResult.Rejected("sendReport failed")
    }

    /** Connected hosts as `BluetoothDevice`s (an empty list means "nothing to send to"). */
    private fun deviceTargets(): List<BluetoothDevice> {
        val localAdapter = adapter ?: return emptyList()
        return _state.value.connectedDevices.mapNotNull { address ->
            runCatching { localAdapter.getRemoteDevice(address) }.getOrNull()
        }
    }

    /**
     * Blocks the caller until the throttle allows the next report.
     *
     * Called on the session's own single-thread executor chain (the UI invokes
     * [sendKey] from a coroutine on Dispatchers.Default), so a short wait can not
     * stall the main thread.
     */
    private fun awaitThrottleSlot() {
        val wait = throttle.waitMs()
        if (wait > 0) runCatching { Thread.sleep(wait) }
    }

    /** Tears the session down: disconnect peers, unregister, close the proxy. */
    fun stop() {
        val hid = proxy
        val localAdapter = adapter
        if (hid != null) {
            _state.value.connectedDevices.forEach { address ->
                localAdapter?.getRemoteDevice(address)?.let { runCatching { hid.disconnect(it) } }
            }
            if (registered) {
                runCatching { hid.unregisterApp() }
                event("unregisterApp()")
            }
        }
        registered = false
        if (hid != null && localAdapter != null) {
            runCatching { localAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) }
        }
        proxy = null
        keyboard.releaseAll()
        throttle.reset()
        publish(phase = SessionPhase.Idle, connected = emptyList())
    }

    /**
     * Sends one report to one host.
     *
     * Wire format: [sendReport] takes the report ID separately, so the byte array
     * is the report body only (modifier, reserved, 6 slots). The descriptor in
     * [ProbeConstants.KEYBOARD_REPORT_DESCRIPTOR] declares no Report ID, which means
     * the single report is addressed as ID [REPORT_ID].
     *
     * Verified against the platform jar: API 36 exposes
     * `sendReport(BluetoothDevice, int, byte[])`; there is no array+length overload.
     */
    private fun send(hid: BluetoothHidDevice, device: BluetoothDevice, report: HidReport): Boolean {
        val bytes = report.toByteArray()
        val ok = runCatching { hid.sendReport(device, REPORT_ID, bytes) }.getOrElse { error ->
            event("sendReport threw ${error.javaClass.simpleName}: ${error.message}")
            false
        }
        throttle.markSent()
        return ok
    }

    // ---------- bookkeeping ----------

    private fun bondedDevices(localAdapter: BluetoothAdapter): List<PairedDevice> =
        runCatching {
            localAdapter.bondedDevices.orEmpty().map { device ->
                PairedDevice(name = safeName(device), address = device.address ?: "-", device = device)
            }.sortedBy { it.name }
        }.getOrElse { error ->
            event("bondedDevices failed: ${error.message}")
            emptyList()
        }

    private fun rememberConnected(device: BluetoothDevice) {
        val address = device.address ?: return
        val existing = _state.value.connectedDevices
        if (address !in existing) publish(connected = existing + address)
    }

    private fun forgetConnected(device: BluetoothDevice) {
        val address = device.address ?: return
        publish(connected = _state.value.connectedDevices - address)
    }

    private fun publish(
        phase: SessionPhase? = null,
        connected: List<String>? = null,
        paired: List<PairedDevice>? = null,
    ) {
        _state.value = _state.value.copy(
            phase = phase ?: _state.value.phase,
            registered = registered,
            connectedDevices = connected ?: _state.value.connectedDevices,
            pairedDevices = paired ?: _state.value.pairedDevices,
            log = events.toList(),
        )
    }

    private fun event(line: String) {
        Log.i(tag, "session: $line")
        if (events.size >= MAX_EVENTS) events.removeFirst()
        events.addLast(line)
        _state.value = _state.value.copy(registered = registered, log = events.toList())
        onEvent(line)
    }

    /**
     * Starts (or extends) a burst of identical keystrokes.
     *
     * Auto-repeat produces a report every ~60 ms. Logging each one scrolls the
     * on-screen log faster than it can be read, so a burst is summarised once it
     * ends ("tap KEYCODE_DEL x40") instead of line by line. The full stream is still
     * available through `adb logcat` (see [event]).
     */
    private fun noteBurst(keyCode: Int, usage: Int) {
        if (burstUsage == usage) {
            burstCount++
        } else {
            flushBurst()
            burstUsage = usage
            burstKeyCode = keyCode
            burstCount = 1
        }
        burstFlush?.cancel()
        burstFlush = clock.schedule(BURST_IDLE_MS) { flushBurst() }
    }

    /** Emits the pending burst summary, if any. */
    private fun flushBurst() {
        val usage = burstUsage ?: return
        val count = burstCount
        val keyCode = burstKeyCode
        burstUsage = null
        burstCount = 0
        burstFlush?.cancel()
        burstFlush = null
        if (count > 1) {
            event("连发 ${keyName(keyCode)} usage=0x%02X ×$count".format(usage))
        }
    }

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun safeName(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull() ?: device.address ?: "unknown"

    private fun stateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "UNKNOWN($state)"
    }

    private fun keyName(keyCode: Int): String = runCatching {
        android.view.KeyEvent.keyCodeToString(keyCode)
    }.getOrDefault("keyCode=$keyCode")

    private companion object {
        const val MAX_EVENTS = 200

        /** Quiet time after which an auto-repeat burst is summarised into one log line. */
        const val BURST_IDLE_MS = 350L

        /** How long to wait for onAppStatusChanged before assuming a stale registration. */
        const val REGISTER_CONFIRM_DELAY_MS = 1_500L

        /** Gap between unregisterApp and the retry (kernel UHID teardown is async). */
        const val REGISTER_RETRY_DELAY_MS = 700L

        const val MAX_REGISTER_ATTEMPTS = 3

        /**
         * Report ID passed to `sendReport`. The descriptor declares no Report ID,
         * so the single input report is ID 0. If the descriptor ever gains an
         * explicit `0x85 nn`, this must become `nn`.
         */
        const val REPORT_ID = 0
    }
}
