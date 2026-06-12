package dev.focusforlife.android.ui

import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.focusforlife.android.R
import dev.focusforlife.android.accessibility.AppBlockerAccessibilityService
import dev.focusforlife.android.admin.DeviceOwnerController
import dev.focusforlife.android.core.FocusRules
import dev.focusforlife.android.core.FocusTargets
import dev.focusforlife.android.logging.FocusLogger
import dev.focusforlife.android.services.AccessibilityUtils
import dev.focusforlife.android.services.FocusOverlayService
import dev.focusforlife.android.services.FocusVpnService
import dev.focusforlife.android.ui.theme.BrandOrange
import dev.focusforlife.android.ui.theme.DangerRed
import dev.focusforlife.android.ui.theme.FocusForLifeTheme
import dev.focusforlife.android.ui.theme.SuccessGreen
import dev.focusforlife.android.ui.theme.WarnAmber
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Compose dashboard that shows blocker state, remaining quota, and per-app stats.
 *
 * NOTE: the Protection section (device admin toggle, PIN management, disable
 * request, maintenance unlock) is temporarily removed from the UI. The
 * underlying FocusLockManager / DeviceOwnerController logic is untouched.
 */
class MainActivity : ComponentActivity() {

    private var uiState by mutableStateOf(DashboardState())

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FocusLogger.init(this)
        FocusLogger.i("MainActivity created")
        dev.focusforlife.android.core.FocusSync.startListening()
        refreshDashboard()
        setContent {
            FocusForLifeTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    DashboardScreen(
                        state = uiState,
                        onRefresh = { refreshDashboard() },
                        onEnableAccessibility = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onEnableVpn = { requestVpn() },
                        onStopVpn = { stopVpnService() },
                        onStartOverlay = { startOverlayService() },
                        onStopOverlay = { stopOverlayService() },
                        onRequestExactAlarm = { requestExactAlarmPermission() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FocusLogger.v("MainActivity resumed")
        // Self-heal: if the accessibility service is listed but unbound (e.g. after
        // a MIUI "clear all"), this rebinds it without the manual toggle dance.
        AccessibilityUtils.ensureServiceEnabled(this)
        refreshDashboard()
        DeviceOwnerController.applyPolicies(this)
        DeviceOwnerController.enforceLockTask(this)
        ensureBootSurvivalSetup()
    }

    @Deprecated(
        message = "Legacy callback used only for VPN consent; replace with ActivityResult when stable."
    )
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    private fun refreshDashboard() {
        FocusRules.ensureFreshDay(this)
        val blockStatus = FocusRules.blockStatus(this)
        val cooldownSeconds = FocusRules.cooldownRemainingSeconds(this)
        uiState = DashboardState(
            blockStatus = blockStatus,
            hardWindowRange = "${FocusRules.hardBlockStart().format(timeFormatter)} – ${FocusRules.hardBlockEnd().format(timeFormatter)}",
            remainingSeconds = FocusRules.remainingSeconds(this),
            isOverlayRunning = FocusOverlayService.isRunning(),
            sessionRemainingSeconds = FocusRules.sessionRemainingSeconds(this),
            nextLockSeconds = FocusRules.nextLockWindowSeconds(this),
            isAccessibilityEnabled = isAccessibilityEnabled(),
            isAccessibilityConnected = AppBlockerAccessibilityService.isConnected(),
            isVpnRunning = FocusVpnService.isRunning(),
            canSelfHeal = AccessibilityUtils.canSelfHeal(this),
            exactAlarmAllowed = isExactAlarmAllowed(),
            dailyQuotaSeconds = FocusRules.dailyQuotaSeconds(),
            hourlyLimitSeconds = FocusRules.currentHourlyLimitSeconds(),
            blockedApps = FocusTargets.blockedAppPackages,
            blockedDomains = FocusTargets.blockedDomains,
            perAppUsage = FocusRules.getPerAppUsageSeconds(this),
            cooldownRemainingSeconds = cooldownSeconds,
            isCooldownActive = blockStatus == FocusRules.BlockStatus.COOLDOWN && cooldownSeconds > 0
        )
    }

    @Suppress("DEPRECATION")
    private fun requestVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            FocusLogger.i("Requesting VPN permission")
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        FocusRules.ensureFreshDay(this)
        startForegroundServiceCompat(Intent(this, FocusVpnService::class.java))
        FocusLogger.i("VPN service start requested")
        refreshDashboard()
    }

    private fun stopVpnService() {
        FocusLogger.i("VPN service stop requested")
        val intent = Intent(this, FocusVpnService::class.java).apply {
            action = FocusVpnService.ACTION_STOP
        }
        startService(intent)
        stopService(intent)
        refreshDashboard()
    }

    private fun startOverlayService() {
        if (!ensureOverlayPermission()) return
        startForegroundServiceCompat(Intent(this, FocusOverlayService::class.java))
        FocusLogger.i("Overlay service start requested")
        refreshDashboard()
    }

    private fun stopOverlayService() {
        val intent = Intent(this, FocusOverlayService::class.java).apply {
            action = FocusOverlayService.ACTION_STOP
        }
        startService(intent)
        stopService(intent)
        FocusLogger.i("Overlay service stop requested")
        refreshDashboard()
    }

    private fun ensureOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            false
        } else {
            true
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val accessibilityEnabled =
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (!accessibilityEnabled) return false
        val expectedComponent = ComponentName(this, AppBlockerAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        colonSplitter.forEach { flattened ->
            val component = ComponentName.unflattenFromString(flattened)
            if (component == expectedComponent) return true
        }
        return false
    }

    private fun isExactAlarmAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            toast("Exact alarm permission not required on this Android version.")
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:$packageName")
        }
        FocusLogger.i("Requesting exact alarm permission")
        startActivity(intent)
    }

    private fun ensureBootSurvivalSetup() {
        val prefs = getSharedPreferences("ffl_setup", Context.MODE_PRIVATE)
        val batteryDone = prefs.getBoolean("battery_opt_done", false)
        val miuiDone = prefs.getBoolean("miui_autostart_done", false)

        if (!batteryDone && !isBatteryOptimizationIgnored()) {
            requestBatteryOptimizationIgnore()
            prefs.edit().putBoolean("battery_opt_done", true).apply()
        }

        if (!miuiDone && isMiui()) {
            openMiuiAutostart()
            prefs.edit().putBoolean("miui_autostart_done", true).apply()
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimizationIgnore() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun isMiui(): Boolean {
        return try {
            packageManager.getPackageInfo("com.miui.securitycenter", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun openMiuiAutostart() {
        val intent = Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            FocusLogger.w("MIUI autostart page not available: ${e.message}")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    companion object {
        private const val VPN_REQUEST_CODE = 1001
    }
}

data class DashboardState(
    val blockStatus: FocusRules.BlockStatus = FocusRules.BlockStatus.NONE,
    val hardWindowRange: String = "23:30 – 11:00",
    val remainingSeconds: Long = 3600,
    val isOverlayRunning: Boolean = false,
    val sessionRemainingSeconds: Long = 600,
    val nextLockSeconds: Long = 600,
    val isAccessibilityEnabled: Boolean = false,
    val isAccessibilityConnected: Boolean = false,
    val isVpnRunning: Boolean = false,
    val canSelfHeal: Boolean = false,
    val exactAlarmAllowed: Boolean = true,
    val dailyQuotaSeconds: Long = 3600,
    val hourlyLimitSeconds: Long = 600,
    val blockedApps: List<String> = emptyList(),
    val blockedDomains: List<String> = emptyList(),
    val perAppUsage: Map<String, Long> = emptyMap(),
    val cooldownRemainingSeconds: Long = 0L,
    val isCooldownActive: Boolean = false
)

@Composable
fun DashboardScreen(
    state: DashboardState,
    onRefresh: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onEnableVpn: () -> Unit,
    onStopVpn: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onRequestExactAlarm: () -> Unit
) {
    LaunchedEffect(Unit) {
        while (isActive) {
            onRefresh()
            delay(1_000)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        BrandHeader(state)
        HeroStatusCard(state)
        GuardsCard(
            state = state,
            onEnableAccessibility = onEnableAccessibility,
            onEnableVpn = onEnableVpn,
            onStopVpn = onStopVpn,
            onStartOverlay = onStartOverlay,
            onStopOverlay = onStopOverlay,
            onRequestExactAlarm = onRequestExactAlarm
        )
        BlockTargetsCard(state)
        UsageCard(state)
        Text(
            text = "FocusForLife · guard your attention",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun BrandHeader(state: DashboardState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Image(
            painter = painterResource(R.drawable.ic_ffl_logo),
            contentDescription = "FocusForLife logo",
            modifier = Modifier.size(44.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append("Focus") }
                    withStyle(
                        SpanStyle(fontWeight = FontWeight.ExtraBold, color = BrandOrange)
                    ) { append("For") }
                    withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append("Life") }
                },
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Guard your attention",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
        StatusDot(
            healthy = state.isAccessibilityEnabled && state.isAccessibilityConnected,
            size = 12.dp
        )
    }
}

private enum class StatusTone { SUCCESS, WARNING, DANGER }

@Composable
private fun HeroStatusCard(state: DashboardState) {
    val (tone, title, description) = when (state.blockStatus) {
        FocusRules.BlockStatus.HARD_WINDOW -> Triple(
            StatusTone.DANGER,
            "Hibernate window",
            "Lockdown runs ${state.hardWindowRange}. Everything blocked stays off."
        )
        FocusRules.BlockStatus.COOLDOWN -> Triple(
            StatusTone.WARNING,
            "Hourly limit reached",
            "Unlocks in ${formatDuration(state.cooldownRemainingSeconds)} at the top of the hour."
        )
        FocusRules.BlockStatus.QUOTA -> Triple(
            StatusTone.WARNING,
            "Daily quota exhausted",
            "The shared daily allowance is gone — see you tomorrow."
        )
        FocusRules.BlockStatus.NONE -> Triple(
            StatusTone.SUCCESS,
            "Within safe window",
            "Next lock in ${formatDuration(state.nextLockSeconds)}."
        )
    }
    val accent = when (tone) {
        StatusTone.SUCCESS -> SuccessGreen
        StatusTone.WARNING -> WarnAmber
        StatusTone.DANGER -> DangerRed
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(accent.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(accent)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            title.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            Column {
                Text(
                    text = formatDuration(state.remainingSeconds),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "daily time left",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            QuotaBar(
                label = "Today",
                valueText = formatDuration(state.remainingSeconds),
                fraction = state.remainingSeconds.toFloat() /
                    state.dailyQuotaSeconds.coerceAtLeast(1).toFloat(),
                color = BrandOrange
            )
            QuotaBar(
                label = "This hour",
                valueText = formatDuration(state.sessionRemainingSeconds),
                fraction = state.sessionRemainingSeconds.toFloat() /
                    state.hourlyLimitSeconds.coerceAtLeast(1).toFloat(),
                color = MaterialTheme.colorScheme.secondary
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Hard block ${state.hardWindowRange} · Hourly limit ${formatDuration(state.hourlyLimitSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QuotaBar(label: String, valueText: String, fraction: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surface,
            drawStopIndicator = {}
        )
    }
}

@Composable
private fun GuardsCard(
    state: DashboardState,
    onEnableAccessibility: () -> Unit,
    onEnableVpn: () -> Unit,
    onStopVpn: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onRequestExactAlarm: () -> Unit
) {
    SectionCard(title = "Guards") {
        GuardRow(
            label = "App blocker",
            detail = if (state.isAccessibilityEnabled && state.isAccessibilityConnected) {
                "Watching foreground apps"
            } else if (state.isAccessibilityEnabled) {
                "Enabled but not running — repairing…"
            } else {
                "Accessibility service is off"
            },
            healthy = state.isAccessibilityEnabled && state.isAccessibilityConnected,
            actionLabel = if (state.isAccessibilityEnabled && state.isAccessibilityConnected) null else "Enable",
            onAction = onEnableAccessibility
        )
        GuardRow(
            label = "Network blocker",
            detail = if (state.isVpnRunning) "DNS filtering active" else "VPN filter is off",
            healthy = state.isVpnRunning,
            actionLabel = if (state.isVpnRunning) "Stop" else "Enable",
            onAction = if (state.isVpnRunning) onStopVpn else onEnableVpn
        )
        GuardRow(
            label = "Floating timer",
            detail = if (state.isOverlayRunning) "Bubble on screen" else "Bubble hidden",
            healthy = state.isOverlayRunning,
            actionLabel = if (state.isOverlayRunning) "Hide" else "Show",
            onAction = if (state.isOverlayRunning) onStopOverlay else onStartOverlay
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            GuardRow(
                label = "Exact alarms",
                detail = if (state.exactAlarmAllowed) "Precise restarts allowed" else "Needed for reliable restarts",
                healthy = state.exactAlarmAllowed,
                actionLabel = if (state.exactAlarmAllowed) null else "Allow",
                onAction = onRequestExactAlarm
            )
        }
        GuardRow(
            label = "Self-heal",
            detail = if (state.canSelfHeal) {
                "Auto-repairs the blocker after RAM purges"
            } else {
                "Grant once: adb shell pm grant <pkg> WRITE_SECURE_SETTINGS"
            },
            healthy = state.canSelfHeal,
            actionLabel = null,
            onAction = {}
        )
    }
}

@Composable
private fun GuardRow(
    label: String,
    detail: String,
    healthy: Boolean,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        StatusDot(healthy = healthy, size = 10.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (actionLabel != null) {
            Spacer(Modifier.width(8.dp))
            if (healthy) {
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            } else {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun StatusDot(healthy: Boolean, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (healthy) SuccessGreen else DangerRed)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockTargetsCard(state: DashboardState) {
    SectionCard(title = "What's blocked") {
        Text(
            "Apps",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.blockedApps.forEach { TargetChip(it) }
        }
        Text(
            "Websites",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.blockedDomains.forEach { TargetChip(it) }
        }
    }
}

@Composable
private fun TargetChip(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun UsageCard(state: DashboardState) {
    SectionCard(title = "Usage today") {
        if (state.perAppUsage.isEmpty()) {
            Text(
                "No blocked apps opened yet. Keep going!",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val maxUsage = state.perAppUsage.values.max().coerceAtLeast(1)
            state.perAppUsage.entries.sortedByDescending { it.value }.forEach { (pkg, seconds) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            friendlySourceLabel(pkg),
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            formatDuration(seconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LinearProgressIndicator(
                        progress = { seconds.toFloat() / maxUsage.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        drawStopIndicator = {}
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

private fun friendlySourceLabel(id: String): String {
    return if (id.startsWith(FocusRules.DOMAIN_PREFIX)) {
        "Web: ${id.removePrefix(FocusRules.DOMAIN_PREFIX)}"
    } else {
        id
    }
}

private fun formatDuration(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hrs > 0 -> "%dh %02dm".format(hrs, mins)
        mins > 0 -> "%dm %02ds".format(mins, secs)
        else -> "%ds".format(secs)
    }
}
