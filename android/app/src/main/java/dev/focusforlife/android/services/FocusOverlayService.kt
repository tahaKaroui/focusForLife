package dev.focusforlife.android.services

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import dev.focusforlife.android.R
import dev.focusforlife.android.core.FocusRules
import dev.focusforlife.android.logging.FocusLogger
import dev.focusforlife.android.ui.BlockedActivity
import kotlin.math.abs

/**
 * Always-on overlay showing the remaining session/daily time.
 */
class FocusOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: android.view.View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var isExpanded = false
    private var isMinimized = false
    private var stopRequested = false
    private var started = false
    private val handler = Handler(Looper.getMainLooper())
    private var accessibilityCheckTick = 0
    private var unhealthyChecks = 0
    private var lastBubbleState = -1
    private var vibrator: Vibrator? = null
    private var urgentActive = false
    private var takedownFired = false
    private var pulseAnimator: AnimatorSet? = null
    private var pulseTarget: android.view.View? = null
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateOverlay()
            checkAccessibilityPeriodically()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        FocusLogger.init(this)
        FocusForegroundNotifications.ensureChannel(this)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            startForeground(
                FocusForegroundNotifications.OVERLAY_NOTIFICATION_ID,
                FocusForegroundNotifications.buildOverlayNotification(this)
            )
            running = true
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            createOverlay()
            handler.post(updateRunnable)
            started = true
            FocusLogger.i("Overlay service started")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        running = false
        FocusLogger.i("Overlay service stopped")
        if (!stopRequested) {
            ServiceRestartScheduler.schedule(this, FocusOverlayService::class.java)
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!stopRequested) {
            FocusLogger.w("Overlay task removed; scheduling restart")
            ServiceRestartScheduler.schedule(this, FocusOverlayService::class.java)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createOverlay() {
        if (overlayView != null) return
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_focus_status, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 16
        params.y = 120
        windowManager?.addView(view, params)
        overlayView = view
        overlayParams = params
        setExpanded(view, false)
        setMinimized(view, false)
        attachDragHandler(view) { toggleMinimized() }
        updateOverlay()
    }

    private fun removeOverlay() {
        stopPulse()
        urgentActive = false
        overlayView?.let {
            windowManager?.removeView(it)
        }
        overlayView = null
        overlayParams = null
    }

    private fun checkAccessibilityPeriodically() {
        accessibilityCheckTick++
        if (accessibilityCheckTick < ACCESSIBILITY_CHECK_INTERVAL_TICKS) return
        accessibilityCheckTick = 0
        if (AccessibilityUtils.isServiceHealthy(this)) {
            unhealthyChecks = 0
            FocusForegroundNotifications.cancelAccessibilityAlert(this)
            return
        }
        // Require two consecutive bad checks so we don't toggle the secure setting
        // while the framework is still rebinding after a process restart.
        unhealthyChecks++
        if (unhealthyChecks < 2) return
        FocusLogger.w("Accessibility service unhealthy (check #$unhealthyChecks); attempting auto-fix")
        val fixed = AccessibilityUtils.ensureServiceEnabled(this)
        if (!fixed) {
            FocusLogger.w("Accessibility service cannot be auto-fixed; alerting user")
            FocusForegroundNotifications.postAccessibilityDisabledAlert(this)
        }
    }

    private fun updateOverlay() {
        val view = overlayView ?: return
        FocusRules.ensureFreshDay(this)
        val hourlyLeft = FocusRules.sessionRemainingSeconds(this)
        val dailyLeft = FocusRules.remainingSeconds(this)
        val hourlyLimit = FocusRules.currentHourlyLimitSeconds().coerceAtLeast(1)
        val dailyQuota = FocusRules.dailyQuotaSeconds().coerceAtLeast(1)
        val blocked = FocusRules.shouldDenyAccess(this)

        val bubbleLabel = view.findViewById<TextView>(R.id.overlayBubbleLabel)
        val bubbleTime = view.findViewById<TextView>(R.id.overlayBubbleTime)
        bubbleTime.text = format(hourlyLeft)
        bubbleLabel.text = format(dailyLeft)

        val urgent = !blocked && hourlyLeft in 1..URGENT_THRESHOLD_SECONDS
        val bubbleState = when {
            blocked -> STATE_BLOCKED
            urgent -> STATE_URGENT
            else -> STATE_NORMAL
        }
        if (bubbleState != lastBubbleState) {
            lastBubbleState = bubbleState
            val bg = when (bubbleState) {
                STATE_BLOCKED -> R.drawable.overlay_bubble_bg_blocked
                STATE_URGENT -> R.drawable.overlay_bubble_bg_urgent
                else -> R.drawable.overlay_bubble_bg
            }
            view.findViewById<android.view.View>(R.id.overlayBubble).setBackgroundResource(bg)
            view.findViewById<android.view.View>(R.id.overlayMinimized).setBackgroundResource(bg)
        }
        handleUrgency(view, urgent, hourlyLeft)
        maybeTriggerTakedown(hourlyLeft, blocked)

        view.findViewById<TextView>(R.id.overlayTitle).text =
            if (blocked) "BLOCKED" else "TIME LEFT"
        view.findViewById<TextView>(R.id.overlayHourlyValue).text =
            "This hour · ${format(hourlyLeft)}"
        view.findViewById<TextView>(R.id.overlayDailyValue).text =
            "Today · ${format(dailyLeft)}"
        view.findViewById<android.widget.ProgressBar>(R.id.overlayHourlyBar).apply {
            max = hourlyLimit.toInt()
            progress = hourlyLeft.coerceIn(0, hourlyLimit).toInt()
        }
        view.findViewById<android.widget.ProgressBar>(R.id.overlayDailyBar).apply {
            max = dailyQuota.toInt()
            progress = dailyLeft.coerceIn(0, dailyQuota).toInt()
        }
    }

    private fun format(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return "%02d:%02d".format(mins, secs)
    }

    /**
     * Final-countdown drama. During the last [URGENT_THRESHOLD_SECONDS] the bubble
     * pulses, shakes and buzzes with rising intensity so the ending is impossible to
     * miss — and if the user has dwarfed it into the minimized dot, we pop it back
     * open so the shrinking clock is right in their face.
     */
    private fun handleUrgency(view: android.view.View, urgent: Boolean, hourlyLeft: Long) {
        if (urgent) {
            if (!urgentActive) {
                urgentActive = true
                if (isMinimized) setMinimized(view, false)
                startPulse(urgentTarget(view))
            }
            buzz(hourlyLeft)
        } else if (urgentActive) {
            urgentActive = false
            stopPulse()
        }
    }

    /** Pulse whatever the user is currently looking at: the open card, else the bubble. */
    private fun urgentTarget(view: android.view.View): android.view.View {
        val expanded = view.findViewById<android.view.View>(R.id.overlayExpanded)
        return if (expanded.visibility == android.view.View.VISIBLE) {
            expanded
        } else {
            view.findViewById(R.id.overlayBubble)
        }
    }

    private fun startPulse(target: android.view.View) {
        if (pulseAnimator != null && pulseTarget === target) return
        stopPulse()
        pulseTarget = target
        val scaleX = ObjectAnimator.ofFloat(target, "scaleX", 1f, 1.24f)
        val scaleY = ObjectAnimator.ofFloat(target, "scaleY", 1f, 1.24f)
        val shake = ObjectAnimator.ofFloat(target, "translationX", -7f, 7f)
        listOf(scaleX, scaleY).forEach {
            it.duration = 440L
            it.repeatCount = ValueAnimator.INFINITE
            it.repeatMode = ValueAnimator.REVERSE
            it.interpolator = AccelerateDecelerateInterpolator()
        }
        shake.duration = 80L
        shake.repeatCount = ValueAnimator.INFINITE
        shake.repeatMode = ValueAnimator.REVERSE
        pulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, shake)
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseTarget?.apply {
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
        }
        pulseTarget = null
    }

    /** One haptic pop per remaining second, harder as zero approaches. */
    private fun buzz(hourlyLeft: Long) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val closeness = (URGENT_THRESHOLD_SECONDS - hourlyLeft).coerceIn(0, URGENT_THRESHOLD_SECONDS)
        val durationMs = 40L + closeness * 12L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = (70 + closeness * 18).coerceIn(1, 255).toInt()
            v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(durationMs)
        }
    }

    /**
     * The moment the session clock hits zero we take the foreground app down
     * ourselves. The accessibility service only re-blocks on the next app switch,
     * so without this a user idling inside a blocked app at 0 would linger; here the
     * block screen slams up the instant the count expires. Fires once per depletion.
     */
    private fun maybeTriggerTakedown(hourlyLeft: Long, blocked: Boolean) {
        if (hourlyLeft <= 0L && blocked) {
            if (!takedownFired) {
                takedownFired = true
                FocusLogger.i("Session hit 0; overlay slamming up block screen")
                startActivity(
                    Intent(this, BlockedActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                    }
                )
            }
        } else {
            takedownFired = false
        }
    }

    private fun toggleExpanded() {
        val view = overlayView ?: return
        setExpanded(view, !isExpanded)
    }

    private fun toggleMinimized() {
        val view = overlayView ?: return
        setMinimized(view, !isMinimized)
    }

    private fun setExpanded(view: android.view.View, expanded: Boolean) {
        isExpanded = expanded
        val bubble = view.findViewById<android.view.View>(R.id.overlayBubble)
        val expandedView = view.findViewById<android.view.View>(R.id.overlayExpanded)
        val minimizedView = view.findViewById<android.view.View>(R.id.overlayMinimized)
        if (expanded) {
            isMinimized = false
        }
        bubble.visibility =
            if (expanded || isMinimized) android.view.View.GONE else android.view.View.VISIBLE
        minimizedView.visibility =
            if (isMinimized && !expanded) android.view.View.VISIBLE else android.view.View.GONE
        expandedView.visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun setMinimized(view: android.view.View, minimized: Boolean) {
        isMinimized = minimized
        val bubble = view.findViewById<android.view.View>(R.id.overlayBubble)
        val minimizedView = view.findViewById<android.view.View>(R.id.overlayMinimized)
        val expandedView = view.findViewById<android.view.View>(R.id.overlayExpanded)
        if (minimized) {
            isExpanded = false
        }
        bubble.visibility = if (minimized) android.view.View.GONE else android.view.View.VISIBLE
        minimizedView.visibility = if (minimized) android.view.View.VISIBLE else android.view.View.GONE
        expandedView.visibility = android.view.View.GONE
    }

    private fun attachDragHandler(view: android.view.View, onClick: () -> Unit) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        moved = true
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(view, params)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        const val ACTION_STOP = "dev.focusforlife.android.action.STOP_OVERLAY"
        private const val ACCESSIBILITY_CHECK_INTERVAL_TICKS = 15
        private const val URGENT_THRESHOLD_SECONDS = 10L
        private const val STATE_NORMAL = 0
        private const val STATE_URGENT = 1
        private const val STATE_BLOCKED = 2
        @Volatile private var running: Boolean = false
        fun isRunning(): Boolean = running
    }
}
