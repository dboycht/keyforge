package com.dboycht.keyforge.ime

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.dboycht.keyforge.probe.ProbeActivity

/**
 * Entry point for the gear icon in the system input-method picker.
 *
 * Android requires every input method to name a settings activity, and it is what the user
 * reaches from "Language & input". Rather than build a second settings UI, this opens the
 * app's own screen: one settings surface, not two that drift apart.
 */
class ImeSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, ProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
