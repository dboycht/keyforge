package com.dboycht.keyforge.probe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dboycht.keyforge.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The capability probe screen.
 *
 * This activity exists to answer one question on real hardware before any of
 * the keyboard UI gets built: does this phone's Bluetooth stack let an app
 * register as a HID keyboard? It stays landscape because that is the shape the
 * finished app will use.
 */
class ProbeActivity : ComponentActivity() {

    /**
     * The HID app registration is a single global slot: while the probe holds it,
     * the keyboard session can not register (`registerApp` returns false and no
     * `onAppStatusChanged(registered=true)` arrives). Leaving the probe screen
     * therefore unregisters, and coming back re-runs the probe, which registers
     * again through the same path.
     */
    private var registeredProxy: android.bluetooth.BluetoothHidDevice? = null

    private val requiredPermissions = buildList {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val requestPermissions = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { /* Result handling lives in ProbeScreen: it re-runs the probe when the grant succeeds. */ }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProbeScreen(
                        askForPermissions = {
                            requestPermissions.launch(requiredPermissions)
                        },
                        onProxyObtained = { proxy -> registeredProxy = proxy },
                    )
                }
            }
        }
    }

    override fun onStop() {
        // Free the single global HID registration slot for other screens (the
        // minimal keyboard) and for the next probe run.
        registeredProxy?.let { proxy ->
            runCatching { proxy.unregisterApp() }
        }
        registeredProxy = null
        super.onStop()
    }
}

@Composable
private fun ProbeScreen(
    askForPermissions: () -> Unit,
    onProxyObtained: (android.bluetooth.BluetoothHidDevice) -> Unit = {},
) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<ProbeReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var runToken by remember { mutableStateOf(0) }
    var permissionsMissing by remember { mutableStateOf(hasMissingPermissions(context)) }
    // Guards the one-shot auto-request on entry; deny + relaunch asks again, deny
    // inside a session does not loop. The in-app button stays as the manual retry.
    var permissionPromptShown by remember { mutableStateOf(false) }

    // Request the runtime permissions up front instead of silently failing: on
    // API 31+ a missing BLUETOOTH_CONNECT makes BluetoothAdapter#getAddress throw
    // SecurityException, which used to abort the probe before the HID test ran.
    LaunchedEffect(Unit) {
        if (hasMissingPermissions(context) && !permissionPromptShown) {
            permissionPromptShown = true
            askForPermissions()
        }
    }

    LaunchedEffect(runToken) {
        if (!permissionsMissing) {
            running = true
            report = withContext(Dispatchers.IO) {
                BluetoothHidProbe.run(context, onProxyObtained)
            }
            running = false
        }
    }

    // Coming back from the system permission dialog (grant or deny) refreshes the
    // hint and re-runs the probe as soon as the permissions are really there.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val missing = hasMissingPermissions(context)
            permissionsMissing = missing
            if (!missing) {
                runToken++
            } else {
                permissionPromptShown = false
            }
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Header()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { runToken++ }, enabled = !running) {
                    Text(stringResourceOrFallback(context, R.string.probe_run))
                }
                OutlinedButton(
                    onClick = {
                        askForPermissions()
                        // The RESUMED effect above re-runs the probe once the grant lands;
                        // this also refreshes the hint immediately when the user denies.
                        permissionsMissing = hasMissingPermissions(context)
                    },
                ) {
                    Text(stringResourceOrFallback(context, R.string.probe_grant))
                }
                OutlinedButton(
                    onClick = {
                        report?.let {
                            copyToClipboard(context, it.toPlainText())
                            Toast.makeText(
                                context,
                                context.getString(R.string.probe_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    enabled = report != null,
                ) {
                    Text(stringResourceOrFallback(context, R.string.probe_copy))
                }
                if (running) {
                    Spacer(Modifier.width(4.dp))
                    CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
                }
                // Always available: the keyboard screen reports its own live state,
                // and requiring the probe verdict here would hide the entry point on
                // a false negative (which this probe has produced before).
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            android.content.Intent(context, com.dboycht.keyforge.keyboard.KeyboardActivity::class.java),
                        )
                    },
                ) {
                    Text(stringResourceOrFallback(context, R.string.probe_open_keyboard))
                }
            }

            if (permissionsMissing) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "提示：蓝牙权限尚未授予，检测会提前中止。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(14.dp))

            report?.let { current ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (current.fatal) FatalRed else PassGreen,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = "结论",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = current.verdict,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                report?.checks?.forEach { check -> CheckRow(check) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Header() {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            text = stringResourceOrFallback(LocalContext.current, R.string.probe_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResourceOrFallback(LocalContext.current, R.string.probe_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CheckRow(check: ProbeCheck) {
    val accent = when (check.status) {
        ProbeStatus.PASS -> PassGreen
        ProbeStatus.FAIL -> FatalRed
        ProbeStatus.WARN -> WarnAmber
        ProbeStatus.INFO -> InfoBlue
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Text(
                text = check.status.name,
                color = accent,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(54.dp),
            )
            Column {
                Text(
                    text = check.title,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = check.detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val PassGreen = Color(0xFF2E7D32)
private val FatalRed = Color(0xFFB3261E)
private val WarnAmber = Color(0xFFB26A00)
private val InfoBlue = Color(0xFF3B6EA5)

private fun ProbeReport.toPlainText(): String = buildString {
    appendLine("KeyForge capability probe")
    appendLine("verdict: $verdict")
    appendLine("fatal: $fatal")
    checks.forEach { appendLine("${it.status} | ${it.title} | ${it.detail}") }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("KeyForge probe", text))
}

private fun hasMissingPermissions(context: Context): Boolean {
    val connect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
    val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
    return connect != PackageManager.PERMISSION_GRANTED || scan != PackageManager.PERMISSION_GRANTED
}

/** Small helper so a missing string resource can never crash the probe. */
private fun stringResourceOrFallback(context: Context, id: Int): String =
    runCatching { context.getString(id) }.getOrDefault("")
