package com.anil.igbizlogger

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Wraps BiometricPrompt so every sensitive action (opening the app, revealing/changing
 * the export passphrase) is gated by the PHONE'S OWN lock (fingerprint/face/PIN) —
 * not a separate password we invent and have to manage recovery for ourselves.
 */
object BiometricGate {

    private const val ALLOWED = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isAvailable(activity: FragmentActivity): Boolean {
        val mgr = BiometricManager.from(activity)
        return mgr.canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun prompt(activity: FragmentActivity, title: String, subtitle: String, onFail: (() -> Unit)? = null, onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFail?.invoke()
            }
            override fun onAuthenticationFailed() {
                // A single wrong attempt — let the prompt itself keep retrying, no action needed here.
            }
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(ALLOWED)
            .build()
        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
    }
}
