package com.example.barky

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.barky.screen.ScreenCaptureService
import com.example.barky.walkie.WalkieTalkieService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            BarkyPrefs.setScreenCaptureEnabled(this, true)
            ScreenCaptureService.start(this, result.resultCode, result.data!!)
            Toast.makeText(this, R.string.screen_capture_started, Toast.LENGTH_SHORT).show()
            refreshSuperAdminUi()
        } else {
            BarkyPrefs.setScreenCaptureEnabled(this, false)
            Toast.makeText(this, R.string.screen_capture_denied, Toast.LENGTH_SHORT).show()
            findViewById<MaterialCheckBox>(R.id.superadmin_screen_capture).isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setNavigationIconTint(getColor(R.color.barky_on_surface))

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

        setupSuperAdmin()
    }

    private fun setupSuperAdmin() {
        val password = findViewById<TextInputEditText>(R.id.superadmin_password)
        val unlock = findViewById<MaterialButton>(R.id.superadmin_unlock)
        val lock = findViewById<MaterialButton>(R.id.superadmin_lock)
        val capture = findViewById<MaterialCheckBox>(R.id.superadmin_screen_capture)
        val serveApk = findViewById<MaterialCheckBox>(R.id.serve_apk_updates)
        val checkUpdate = findViewById<MaterialButton>(R.id.check_update_internet)

        unlock.setOnClickListener {
            val entered = password.text?.toString().orEmpty()
            if (BarkyPrefs.tryUnlockSuperAdmin(this, entered)) {
                password.setText("")
                Toast.makeText(this, R.string.settings_superadmin_unlocked_toast, Toast.LENGTH_SHORT).show()
                refreshSuperAdminUi()
            } else {
                Toast.makeText(this, R.string.settings_superadmin_bad_password, Toast.LENGTH_SHORT).show()
            }
        }
        lock.setOnClickListener {
            if (BarkyPrefs.isScreenCaptureEnabled(this)) {
                ScreenCaptureService.stop(this)
            }
            BarkyPrefs.setSuperAdmin(this, false)
            Toast.makeText(this, R.string.settings_superadmin_locked_toast, Toast.LENGTH_SHORT).show()
            refreshSuperAdminUi()
        }

        capture.setOnCheckedChangeListener { _, checked ->
            if (!BarkyPrefs.isSuperAdmin(this)) {
                capture.isChecked = false
                return@setOnCheckedChangeListener
            }
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, R.string.screen_capture_need_overlay, Toast.LENGTH_LONG).show()
                }
                screenCaptureLauncher.launch(ScreenCaptureService.createScreenCaptureIntent(this))
            } else {
                BarkyPrefs.setScreenCaptureEnabled(this, false)
                ScreenCaptureService.stop(this)
                Toast.makeText(this, R.string.screen_capture_stopped, Toast.LENGTH_SHORT).show()
            }
        }

        serveApk.isChecked = BarkyPrefs.isServeApkEnabled(this)
        serveApk.setOnCheckedChangeListener { _, checked ->
            BarkyPrefs.setServeApkEnabled(this, checked)
            WalkieTalkieService.start(this)
            Toast.makeText(
                this,
                if (checked) R.string.settings_serve_apk_on else R.string.settings_serve_apk_off,
                Toast.LENGTH_SHORT
            ).show()
        }

        val autoScrn = findViewById<MaterialCheckBox>(R.id.auto_screen_share)
        autoScrn.isChecked = BarkyPrefs.isAutoScreenShare(this)
        autoScrn.setOnCheckedChangeListener { _, checked ->
            BarkyPrefs.setAutoScreenShare(this, checked)
            if (!checked) {
                com.example.barky.screen.MediaProjectionHolder.clear()
            }
        }
        findViewById<MaterialButton>(R.id.prepare_screen_share).setOnClickListener {
            BarkyPrefs.setAutoScreenShare(this, true)
            autoScrn.isChecked = true
            startActivity(
                Intent(this, ScreenShareConsentActivity::class.java).putExtra(
                    ScreenShareConsentActivity.EXTRA_PREPARE_ONLY,
                    true
                )
            )
        }

        checkUpdate.setOnClickListener {
            // Full search: Firebase meta URL + peer IPs (cellular OK) + LAN
            LanApkUpdater(this).checkAndInstall(preferLanScan = true)
        }

        refreshSuperAdminUi()
    }

    private fun refreshSuperAdminUi() {
        val unlocked = BarkyPrefs.isSuperAdmin(this)
        val panel = findViewById<View>(R.id.superadmin_panel)
        val unlock = findViewById<MaterialButton>(R.id.superadmin_unlock)
        val lock = findViewById<MaterialButton>(R.id.superadmin_lock)
        val capture = findViewById<MaterialCheckBox>(R.id.superadmin_screen_capture)
        panel.visibility = if (unlocked) View.VISIBLE else View.GONE
        unlock.visibility = if (unlocked) View.GONE else View.VISIBLE
        lock.visibility = if (unlocked) View.VISIBLE else View.GONE
        findViewById<View>(R.id.superadmin_password_layout).visibility =
            if (unlocked) View.GONE else View.VISIBLE
        capture.setOnCheckedChangeListener(null)
        capture.isChecked = BarkyPrefs.isScreenCaptureEnabled(this)
        capture.setOnCheckedChangeListener { _, checked ->
            if (!BarkyPrefs.isSuperAdmin(this)) {
                capture.isChecked = false
                return@setOnCheckedChangeListener
            }
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, R.string.screen_capture_need_overlay, Toast.LENGTH_LONG).show()
                }
                screenCaptureLauncher.launch(ScreenCaptureService.createScreenCaptureIntent(this))
            } else {
                BarkyPrefs.setScreenCaptureEnabled(this, false)
                ScreenCaptureService.stop(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSuperAdminUi()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
