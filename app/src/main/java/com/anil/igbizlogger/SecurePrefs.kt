package com.anil.igbizlogger

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Every call is guarded: if the Android Keystore/EncryptedSharedPreferences setup fails
 * for any reason (seen in the wild on some devices/Android versions with this library),
 * we fall back to reading/writing nothing rather than crashing the app. Worst case,
 * app lock or the passphrase just isn't remembered that session, not a force-close.
 */
object SecurePrefs {
    private const val FILE = "biz_logger_secure_prefs"
    private const val KEY_PASSPHRASE = "export_passphrase"
    private const val KEY_DRIVE_ACCOUNT = "drive_account_email"
    private const val KEY_AUTO_DELETE_HOURS = "auto_delete_delay_hours"
    private const val KEY_APP_LOCK_HASH = "app_lock_pin_hash"

    private fun prefs(context: Context): SharedPreferences? = try {
        EncryptedSharedPreferences.create(
            context, FILE,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        AppLog.log(context, "SecurePrefs unavailable: ${e.message}")
        null
    }

    fun getPassphrase(context: Context): String? = prefs(context)?.getString(KEY_PASSPHRASE, null)
    fun setPassphrase(context: Context, value: String) { prefs(context)?.edit()?.putString(KEY_PASSPHRASE, value)?.apply() }

    fun getDriveAccount(context: Context): String? = prefs(context)?.getString(KEY_DRIVE_ACCOUNT, null)
    fun setDriveAccount(context: Context, value: String) { prefs(context)?.edit()?.putString(KEY_DRIVE_ACCOUNT, value)?.apply() }

    /** 0 = delete immediately after upload. */
    fun getAutoDeleteDelayHours(context: Context): Int = prefs(context)?.getInt(KEY_AUTO_DELETE_HOURS, 0) ?: 0
    fun setAutoDeleteDelayHours(context: Context, hours: Int) { prefs(context)?.edit()?.putInt(KEY_AUTO_DELETE_HOURS, hours)?.apply() }

    fun getAppLockHash(context: Context): String? = prefs(context)?.getString(KEY_APP_LOCK_HASH, null)
    fun setAppLockHash(context: Context, value: String?) { prefs(context)?.edit()?.putString(KEY_APP_LOCK_HASH, value)?.apply() }

    private const val KEY_EXPORT_MODE = "export_mode"
    /** One of: PERIODIC_1, PERIODIC_6, PERIODIC_24 (default), ON_OPEN, MANUAL */
    fun getExportMode(context: Context): String = prefs(context)?.getString(KEY_EXPORT_MODE, "PERIODIC_24") ?: "PERIODIC_24"
    fun setExportMode(context: Context, value: String) { prefs(context)?.edit()?.putString(KEY_EXPORT_MODE, value)?.apply() }

    private const val KEY_LAST_EXPORT_TRIGGER = "last_export_trigger_millis"
    fun getLastExportTriggerMillis(context: Context): Long = prefs(context)?.getLong(KEY_LAST_EXPORT_TRIGGER, 0L) ?: 0L
    fun setLastExportTriggerMillis(context: Context, value: Long) { prefs(context)?.edit()?.putLong(KEY_LAST_EXPORT_TRIGGER, value)?.apply() }
}
