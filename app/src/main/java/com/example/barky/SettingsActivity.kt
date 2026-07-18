package com.example.barky

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.barky.walkie.WalkieTalkieService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setNavigationIconTint(getColor(R.color.barky_on_primary))

        findViewById<MaterialButton>(R.id.enable_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<MaterialButton>(R.id.enable_overlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                Toast.makeText(this, R.string.settings_overlay_granted, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.start_service).setOnClickListener {
            Toast.makeText(this, R.string.settings_setup_steps, Toast.LENGTH_LONG).show()
        }

        val wifiOnly = findViewById<MaterialCheckBox>(R.id.walkie_wifi_only)
        wifiOnly.isChecked = BarkyPrefs.isWifiOnly(this)
        wifiOnly.setOnCheckedChangeListener { _, checked ->
            BarkyPrefs.setWifiOnly(this, checked)
            WalkieTalkieService.applyNetworkMode(this)
            Toast.makeText(
                this,
                if (checked) R.string.settings_wifi_only_saved_on
                else R.string.settings_wifi_only_saved_off,
                Toast.LENGTH_SHORT
            ).show()
        }

        val firebaseReg = findViewById<MaterialCheckBox>(R.id.firebase_registry_enabled)
        val firebaseHelp = findViewById<android.widget.TextView>(R.id.firebase_device_id_label)
        val deviceId = com.example.barky.net.DeviceIdentity.deviceId(this)
        firebaseHelp.text = getString(R.string.settings_firebase_registry_help, deviceId)
        firebaseReg.isChecked = BarkyPrefs.isFirebaseRegistryEnabled(this)
        firebaseReg.setOnCheckedChangeListener { _, checked ->
            BarkyPrefs.setFirebaseRegistryEnabled(this, checked)
            if (checked && !com.example.barky.net.FirebasePeerDirectory.isConfigured(this)) {
                Toast.makeText(this, R.string.settings_firebase_not_configured, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(
                    this,
                    if (checked) R.string.settings_firebase_on else R.string.settings_firebase_off,
                    Toast.LENGTH_SHORT
                ).show()
            }
            // Nudge service to register soon
            WalkieTalkieService.start(this)
        }

        val conversion = findViewById<MaterialCheckBox>(R.id.unit_conversion_enabled)
        conversion.isChecked = BarkyPrefs.isConversionEnabled(this)
        conversion.setOnCheckedChangeListener { _, checked ->
            BarkyPrefs.setConversionEnabled(this, checked)
            Toast.makeText(
                this,
                if (checked) R.string.settings_conversion_on else R.string.settings_conversion_off,
                Toast.LENGTH_SHORT
            ).show()
        }

        val delayEdit = findViewById<TextInputEditText>(R.id.delay_edit)
        val prefs = getSharedPreferences(BarkyPrefs.NAME, MODE_PRIVATE)
        delayEdit.setText(prefs.getInt(BarkyPrefs.KEY_HIDE_DELAY, 20).toString())

        findViewById<MaterialButton>(R.id.save_delay).setOnClickListener {
            val delay = delayEdit.text?.toString()?.toIntOrNull()?.coerceIn(1, 300) ?: 20
            prefs.edit().putInt(BarkyPrefs.KEY_HIDE_DELAY, delay).apply()
            delayEdit.setText(delay.toString())
            Toast.makeText(
                this,
                getString(R.string.settings_delay_saved, delay),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
