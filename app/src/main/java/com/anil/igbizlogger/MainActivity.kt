package com.anil.igbizlogger

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import androidx.appcompat.widget.SwitchCompat
import com.google.api.services.drive.DriveScopes
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File

class MainActivity : AppCompatActivity() {

    private val autoDeleteOptions = listOf("Immediately" to 0, "After 1 day" to 24, "After 3 days" to 72, "After 7 days" to 168)
    private val exportModeOptions = listOf(
        "Every 24 hours" to "PERIODIC_24",
        "Every 6 hours" to "PERIODIC_6",
        "Every 1 hour" to "PERIODIC_1",
        "Every time Instagram opens (max once per 30 min)" to "ON_OPEN",
        "Manual only" to "MANUAL"
    )
    private var suppressLockSwitchEvent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try { DailyExportWorker.schedule(applicationContext) } catch (e: Exception) { AppLog.log(this, "Worker schedule failed: ${e.message}") }

        findViewById<View>(R.id.btnEnable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<View>(R.id.btnSavePassphrase).setOnClickListener {
            val pass = findViewById<EditText>(R.id.etPassphrase).text.toString()
            if (pass.length < 8) { Toast.makeText(this, "Use at least 8 characters", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            requireAuth("Confirm it's you", "Changing the export passphrase") {
                SecurePrefs.setPassphrase(this, pass)
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.btnShowPassphrase).setOnClickListener {
            requireAuth("Confirm it's you", "Revealing the export passphrase") {
                val p = SecurePrefs.getPassphrase(this) ?: "(not set yet)"
                AlertDialog.Builder(this).setTitle("Current passphrase").setMessage(p).setPositiveButton("Close", null).show()
            }
        }

        findViewById<View>(R.id.btnConnectDrive).setOnClickListener {
            try {
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestScopes(Scope(DriveScopes.DRIVE_FILE)).build()
                startActivityForResult(GoogleSignIn.getClient(this, gso).signInIntent, REQ_SIGN_IN)
            } catch (e: Exception) {
                AppLog.log(this, "Drive sign-in launch failed: ${e.message}")
                Toast.makeText(this, "Drive sign-in isn't available right now.", Toast.LENGTH_SHORT).show()
            }
        }

        val spinner = findViewById<Spinner>(R.id.spinnerAutoDelete)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, autoDeleteOptions.map { it.first })
        val currentHours = SecurePrefs.getAutoDeleteDelayHours(this)
        spinner.setSelection(autoDeleteOptions.indexOfFirst { it.second == currentHours }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                SecurePrefs.setAutoDeleteDelayHours(this@MainActivity, autoDeleteOptions[pos].second)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val exportModeSpinner = findViewById<Spinner>(R.id.spinnerExportMode)
        exportModeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, exportModeOptions.map { it.first })
        val currentMode = SecurePrefs.getExportMode(this)
        exportModeSpinner.setSelection(exportModeOptions.indexOfFirst { it.second == currentMode }.coerceAtLeast(0))
        exportModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                SecurePrefs.setExportMode(this@MainActivity, exportModeOptions[pos].second)
                try { DailyExportWorker.schedule(applicationContext) } catch (e: Exception) { AppLog.log(this@MainActivity, "Reschedule failed: ${e.message}") }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        findViewById<View>(R.id.btnExportNow).setOnClickListener {
            WorkManager.getInstance(applicationContext).enqueue(OneTimeWorkRequestBuilder<DailyExportWorker>().build())
            Toast.makeText(this, "Export started — check App logs shortly for the result.", Toast.LENGTH_SHORT).show()
        }

        val lockSwitch = findViewById<SwitchCompat>(R.id.switchAppLock)
        suppressLockSwitchEvent = true
        lockSwitch.isChecked = SecurePrefs.getAppLockHash(this) != null
        suppressLockSwitchEvent = false
        lockSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressLockSwitchEvent) return@setOnCheckedChangeListener
            if (isChecked) {
                requireAuth("Confirm it's you", "Turning on app lock", onFail = {
                    suppressLockSwitchEvent = true; lockSwitch.isChecked = false; suppressLockSwitchEvent = false
                }) {
                    SecurePrefs.setAppLockHash(this, "enabled")
                    Toast.makeText(this, "App lock on.", Toast.LENGTH_SHORT).show()
                }
            } else {
                requireAuth("Confirm it's you", "Turning off app lock", onFail = {
                    suppressLockSwitchEvent = true; lockSwitch.isChecked = true; suppressLockSwitchEvent = false
                }) {
                    SecurePrefs.setAppLockHash(this, null)
                    Toast.makeText(this, "App lock off.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<View>(R.id.btnHideIcon).setOnClickListener {
            val alias = ComponentName(this, "com.anil.igbizlogger.Launcher")
            packageManager.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            Toast.makeText(this, "Icon hidden. Re-enable from Settings > Apps > Biz Logger.", Toast.LENGTH_LONG).show()
        }

        findViewById<View>(R.id.btnViewLogs).setOnClickListener {
            AlertDialog.Builder(this).setTitle("App logs").setMessage(AppLog.readAll(this)).setPositiveButton("Close", null).show()
        }

        findViewById<View>(R.id.btnDeleteAll).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete all logged data?")
                .setMessage("Permanently deletes every stored conversation log and pending export on this device. Can't be undone.")
                .setPositiveButton("Delete everything") { _, _ ->
                    MessageStore(applicationContext).deleteAllData()
                    File(getExternalFilesDir(null), "IGBizExports").deleteRecursively()
                    Toast.makeText(this, "All data deleted.", Toast.LENGTH_SHORT).show()
                    refreshStatus()
                }
                .setNegativeButton("Cancel", null).show()
        }

        findViewById<View>(R.id.btnUnlock).setOnClickListener { tryUnlock() }
    }

    private fun requireAuth(title: String, subtitle: String, onFail: (() -> Unit)? = null, onSuccess: () -> Unit) {
        if (BiometricGate.isAvailable(this)) {
            BiometricGate.prompt(this, title, subtitle, onFail = {
                Toast.makeText(this, "Authentication failed.", Toast.LENGTH_SHORT).show()
                onFail?.invoke()
            }, onSuccess = onSuccess)
        } else {
            onSuccess()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SIGN_IN) {
            val account = GoogleSignIn.getLastSignedInAccount(this)
            if (account != null) {
                SecurePrefs.setDriveAccount(this, account.email ?: "unknown")
                Toast.makeText(this, "Drive connected: ${account.email}", Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        val overlay = findViewById<View>(R.id.lockOverlay)
        if (SecurePrefs.getAppLockHash(this) != null) {
            overlay.alpha = 1f
            overlay.visibility = View.VISIBLE
            tryUnlock()
        }
    }

    private fun tryUnlock() {
        val overlay = findViewById<View>(R.id.lockOverlay)
        if (!BiometricGate.isAvailable(this)) { overlay.visibility = View.GONE; return }
        BiometricGate.prompt(this, "Unlock Biz Logger", "") {
            // Small fade-out instead of an abrupt disappearance.
            overlay.animate().alpha(0f).setDuration(220).withEndAction { overlay.visibility = View.GONE }.start()
        }
    }

    private fun refreshStatus() {
        val status = findViewById<TextView>(R.id.tvStatus)
        val enabled = isAccessibilityServiceEnabled()
        val logDir = File(getExternalFilesDir(null), "IGBizLogs")
        val driveAccount = SecurePrefs.getDriveAccount(this)
        status.text = buildString {
            append(if (enabled) "Logger is ON  \u00B7  " else "Logger is OFF  \u00B7  ")
            append(if (driveAccount != null) "Drive: $driveAccount" else "Drive: not connected")
        }
        findViewById<TextView>(R.id.tvAbout).text = buildString {
            append("Version ${BuildConfig.VERSION_NAME}\n")
            append("Owner: this device's account holder\n")
            append(logDir.absolutePath)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "${packageName}/${InstaLoggerService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        for (s in splitter) { if (s.equals(expected, ignoreCase = true)) return true }
        return false
    }

    companion object { private const val REQ_SIGN_IN = 1001 }
}
