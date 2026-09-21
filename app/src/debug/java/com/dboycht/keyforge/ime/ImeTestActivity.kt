package com.dboycht.keyforge.ime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Debug-only screen whose only job is to **give the input method somewhere to appear**.
 *
 * Why it exists: the app's own screens have no editable field, so there was no way to
 * verify "the IME can be selected and its view shows up" without touching another app or
 * the system settings on the device. This screen is a plain text box - open it, tap the
 * box, and the current input method (including 键铸输入法) is what appears at the bottom.
 *
 * Debug-only on purpose: it is a test harness, not a feature, so it is excluded from the
 * release APK by the build type (see `app/src/debug/AndroidManifest.xml`).
 */
class ImeTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ImeTestScreen()
                }
            }
        }
    }
}

@Composable
private fun ImeTestScreen() {
    var text by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "输入法测试页（仅调试版）",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = "点下面的输入框 → 屏幕底部会出现当前输入法。" +
                "要测「键铸输入法」：先在键盘页连上主机，再切换输入法到键铸，然后在这里输入并点「发送」。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("在这里输入（输入法会出现在屏幕底部）") },
        )
        Text(
            text = "当前内容：$text",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
