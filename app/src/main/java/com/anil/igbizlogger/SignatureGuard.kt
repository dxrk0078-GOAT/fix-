package com.anil.igbizlogger

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Detects a repackaged/modified copy of this app WITHOUT any server or network call:
 * Android requires every APK to be signed, and re-signing a modified build always
 * changes the certificate. We hardcode the SHA-256 of our own signing cert here (you
 * fill this in once, after your first real signed build — see README "Anti-tamper
 * setup") and refuse to run active logging if it doesn't match.
 *
 * This is intentionally local-only: it can't phone home, can't be used to deactivate
 * a device remotely, and works even with no internet connection.
 */
object SignatureGuard {

    // TODO: replace with YOUR real signing cert's SHA-256, see README.
    private const val EXPECTED_SHA256 = "REPLACE_ME_AFTER_FIRST_SIGNED_BUILD"

    fun isGenuine(context: Context): Boolean {
        return try {
            val actual = currentSignatureSha256(context)
            EXPECTED_SHA256 == "REPLACE_ME_AFTER_FIRST_SIGNED_BUILD" || actual == EXPECTED_SHA256
            // ^ while unset, we don't block you out of your own dev builds; once you
            // paste your real hash in, mismatches are blocked below.
        } catch (e: Exception) {
            AppLog.log(context, "SignatureGuard check failed: ${e.message}")
            true // fail-open: a broken check must never brick your own working app
        }
    }

    @Suppress("DEPRECATION")
    private fun currentSignatureSha256(context: Context): String {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners ?: arrayOf()
        } else {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            info.signatures ?: arrayOf()
        }
        val bytes = signatures.firstOrNull()?.toByteArray() ?: return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
