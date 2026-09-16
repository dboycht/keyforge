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
        ) { /* The probe re-reads permission state itself; no action needed here. */ }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProbeScreen(
                        onRequestPermissions = { requestPermissions.launch(requiredPermissions) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProbeScreen(onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<ProbeReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var runToken by remember { mutableStateOf(0) }
    var permissionsMissing by remember { mutableStateOf(hasMissingPermissions(context)) }

    LaunchedEffect(runToken) {
        running = true
        report = withContext(Dispatchers.IO) { BluetoothHidProbe.run(context) }
        running = false
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
                        onRequestPermissions()
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
