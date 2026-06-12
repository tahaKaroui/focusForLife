package dev.focusforlife.android.services

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import dev.focusforlife.android.accessibility.AppBlockerAccessibilityService
import dev.focusforlife.android.logging.FocusLogger

object AccessibilityUtils {

    private const val REBIND_DELAY_MS = 700L
    @Volatile private var rebindInFlight = false

    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AppBlockerAccessibilityService::class.java)
        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(flat)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next()) ?: continue
            if (component == expected) return true
        }
        return false
    }

    /**
     * Enabled in settings does NOT mean alive: after MIUI's "clear all" RAM purge the
     * service stays listed but is unbound ("Not working. Tap for info"). Healthy means
     * both listed AND currently bound to the framework.
     */
    fun isServiceHealthy(context: Context): Boolean {
        return isServiceEnabled(context) && AppBlockerAccessibilityService.isConnected()
    }

    fun canSelfHeal(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Programmatically re-enables our accessibility service.
     * Requires WRITE_SECURE_SETTINGS, granted once via:
     *   adb shell pm grant dev.focusforlife.android android.permission.WRITE_SECURE_SETTINGS
     */
    fun ensureServiceEnabled(context: Context): Boolean {
        if (isServiceHealthy(context)) return true
        if (isServiceEnabled(context)) {
            // Listed but unbound (the post-"clear all" zombie state): toggling the
            // secure setting off and back on forces the framework to rebind without
            // the manual 10-second confirmation flow.
            return forceRebind(context)
        }
        return try {
            val component = ComponentName(context, AppBlockerAccessibilityService::class.java)
            val flat = component.flattenToString()
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val newValue = if (current.isBlank()) flat else "$current:$flat"
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newValue
            )
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            FocusLogger.i("Accessibility service re-enabled programmatically")
            true
        } catch (e: SecurityException) {
            FocusLogger.w("WRITE_SECURE_SETTINGS not granted — cannot auto-enable accessibility: ${e.message}")
            false
        }
    }

    /** Removes the service from the enabled list, then re-adds it shortly after. */
    fun forceRebind(context: Context): Boolean {
        if (!canSelfHeal(context)) {
            FocusLogger.w("WRITE_SECURE_SETTINGS not granted — cannot force a11y rebind")
            return false
        }
        if (rebindInFlight) return true
        return try {
            val appContext = context.applicationContext
            val component = ComponentName(appContext, AppBlockerAccessibilityService::class.java)
            val flat = component.flattenToString()
            val current = Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val without = current.split(':')
                .filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != component }
                .joinToString(":")
            rebindInFlight = true
            FocusLogger.w("Forcing accessibility rebind (zombie state detected)")
            Settings.Secure.putString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                without
            )
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val value = if (without.isBlank()) flat else "$without:$flat"
                    Settings.Secure.putString(
                        appContext.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        value
                    )
                    Settings.Secure.putInt(
                        appContext.contentResolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1
                    )
                    FocusLogger.i("Accessibility rebind: service re-added to enabled list")
                } catch (e: Exception) {
                    FocusLogger.e("Accessibility rebind re-add failed", e)
                } finally {
                    rebindInFlight = false
                }
            }, REBIND_DELAY_MS)
            true
        } catch (e: Exception) {
            rebindInFlight = false
            FocusLogger.e("Accessibility force rebind failed", e)
            false
        }
    }
}
