package com.os4.immersive

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/** StandBy itself: landscape, over the lock screen, screen kept on while it is shown. */
class StandbyActivity : Activity() {

    private lateinit var view: StandbyView
    private var leaving = false

    private val close = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = leave()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        view = StandbyView(this)
        view.setSide(intent.getIntExtra(EXTRA_SIDE, 1), false)
        val backdrop = BackdropView(this).apply {
            artEnabled = Prefs.blurBackdrop(this@StandbyActivity)
            alpha = 0f
        }
        view.onMatrix = {
            backdrop.pageMatrix = it
            toScreen = it
            picker?.let(::turn)
            menu?.let(::turn)
            settings?.let(::turn)
            manager?.let(::turn)
        }
        view.followers = listOf(backdrop)
        view.onShown = { backdrop.alpha = kotlin.math.min(1f, it * 1.6f) }
        view.onBurnIn = { backdrop.burnIn = it }
        layer = WidgetLayer(this, view)
        view.overlayHost = layer
        view.onSlotTap = { pick(it, replace = false) }
        view.onExit = {
            StandbyService.hold(this)
            leave()
        }
        view.onMenu = { key, box -> if (key == null || box == null) menu?.close() else showMenu(key, box) }
        view.stackInfo = { key -> stackCount[key]?.let { (stackIndex[key] ?: 0).coerceIn(0, it - 1) to it } }
        view.stackPosOf = { stackPos[it] }
        view.onRemoveWidget = ::removeShown
        view.onWidgetDrop = ::drop
        view.onPagesChanged = { Prefs.setPageIds(this, it) }
        view.onDeletePage = ::deletePage
        view.widgetGlass = Prefs.widgetGlass(this)
        view.onWidgetGlass = { level, done ->
            for (frame in view.hosted.values) {
                if (frame !is android.view.ViewGroup) continue
                for (i in 0 until frame.childCount) (frame.getChildAt(i) as? HiWidget)?.glass = level
            }
            if (done) Prefs.setWidgetGlass(this, level)
        }
        view.widgetsDark = Prefs.allDark(this) ?: true
        view.onWidgetsDark = { on ->
            Prefs.setAllDark(this, on)
            for (frame in view.hosted.values) {
                if (frame !is android.view.ViewGroup) continue
                for (i in 0 until frame.childCount) (frame.getChildAt(i) as? HiWidget)?.fadeDark(on)
            }
        }
        layer.onStackScroll = ::scrollStack
        root = android.widget.FrameLayout(this).apply {
            addView(backdrop)
            addView(view)
            addView(layer)
        }
        setContentView(root)
        loadWidgets()
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Back runs the leave animation first; onBackPressed never hears the back gesture. Over the
        // lock screen StandBy only goes when the phone is picked up, as the gestures are off.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            picker?.close() ?: settings?.back() ?: menu?.close() ?: managedKey?.let { closeManager() }
                ?: if (!getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) leave() else Unit
        }
        registerReceiver(close, IntentFilter(ACTION_CLOSE), RECEIVER_NOT_EXPORTED)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        view.onTimeOff = {
            EdgeLock.sleep(this)
            goAway()
        }
        getSystemService(android.app.NotificationManager::class.java).cancel(StandbyService.SHOW_ID)
        view.post { view.enter() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        view.setSide(intent.getIntExtra(EXTRA_SIDE, 1), false)
    }

    /** Turned over onto the other long edge while shown: the page turns with it. */
    private val sideListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val x = e.values[0]
            // The same test as entering, so a deep recline turns the page too.
            if (StandbyDetector.isLandscape(x, e.values[1], false)) view.setSide(if (x > 0) 1 else -1, true)
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }

    override fun onResume() {
        super.onResume()
        view.idleAfterMs = Prefs.idleSeconds(this).let { if (it == 0) StandbyView.NEVER_MS else it * 1000L }
        view.offAfterMs = Prefs.offSeconds(this) * 1000L
        view.wake()
        val sm = getSystemService(android.hardware.SensorManager::class.java)
        sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(sideListener, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
    }

    // ------------------------------------------------------------------ edge gestures

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Over the lock screen the system's edge gestures stay off while StandBy is up ([EdgeLock]). */
    private val edgeLock = object : Runnable {
        override fun run() {
            EdgeLock.send(this@StandbyActivity,
                getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            handler.postDelayed(this, EdgeLock.REPEAT_MS)
        }
    }

    // ------------------------------------------------------------------ burn-in

    private var driftX = 0
    private var driftY = 0

    /**
     * Against burn-in, everything drawn over the backdrop moves 1px a minute in a random
     * direction, wandering no more than [DRIFT_MAX_PX] from where it belongs.
     */
    private val drift = object : Runnable {
        override fun run() {
            var dx: Int
            var dy: Int
            do {
                dx = (-1..1).random()
                dy = (-1..1).random()
            } while (dx == 0 && dy == 0)
            if (kotlin.math.abs(driftX + dx) > DRIFT_MAX_PX) dx = -dx
            if (kotlin.math.abs(driftY + dy) > DRIFT_MAX_PX) dy = -dy
            driftX += dx
            driftY += dy
            view.drift(driftX.toFloat(), driftY.toFloat())
            layer.translationX = driftX.toFloat()
            layer.translationY = driftY.toFloat()
            handler.postDelayed(this, DRIFT_MS)
        }
    }

    // ------------------------------------------------------------------ status

    private var cell = -1
    private var wifi = -1
    private var level = 1f
    private var charging = false

    private fun pushStatus() = runOnUiThread { view.setStatus(cell, wifi, level, charging) }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, scale) / scale.coerceAtLeast(1).toFloat()
            val st = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, 0)
            charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                st == android.os.BatteryManager.BATTERY_STATUS_FULL
            pushStatus()
        }
    }

    /** Wi-Fi's strength as three arcs, from the network's own signal strength (RSSI). */
    private val wifiCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: android.net.Network, caps: android.net.NetworkCapabilities) {
            val wm = getSystemService(android.net.wifi.WifiManager::class.java)
            val rssi = caps.signalStrength
            wifi = if (rssi == android.net.NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) 3
            else Math.round(3f * wm.calculateSignalLevel(rssi) / wm.maxSignalLevel.coerceAtLeast(1))
            pushStatus()
        }

        override fun onLost(n: android.net.Network) {
            wifi = -1
            pushStatus()
        }
    }

    /** Mobile signal as four bars; none without a SIM in service, or in airplane mode. */
    private val cellCallback = object : android.telephony.TelephonyCallback(),
        android.telephony.TelephonyCallback.SignalStrengthsListener {
        override fun onSignalStrengthsChanged(ss: android.telephony.SignalStrength) {
            cell = cellLevel(ss)
            pushStatus()
        }
    }

    private fun cellLevel(ss: android.telephony.SignalStrength?): Int {
        val tm = getSystemService(android.telephony.TelephonyManager::class.java)
        val airplane = android.provider.Settings.Global.getInt(contentResolver,
            android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        if (airplane || tm.simState != android.telephony.TelephonyManager.SIM_STATE_READY || ss == null) return -1
        return ss.level.coerceIn(0, 4)
    }

    private fun startStatus() {
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        cm.registerNetworkCallback(android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback)
        val tm = getSystemService(android.telephony.TelephonyManager::class.java)
        cell = cellLevel(tm.signalStrength)
        tm.registerTelephonyCallback(mainExecutor, cellCallback)
        pushStatus()
    }

    private fun stopStatus() {
        unregisterReceiver(batteryReceiver)
        runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback) }
        getSystemService(android.telephony.TelephonyManager::class.java).unregisterTelephonyCallback(cellCallback)
    }

    override fun onStart() {
        super.onStart()
        startStatus()
        Spectrum.start(this)
        edgeLock.run()
        handler.postDelayed(drift, DRIFT_MS)
    }

    override fun onStop() {
        handler.removeCallbacks(edgeLock)
        handler.removeCallbacks(drift)
        EdgeLock.send(this, false)
        Spectrum.stop()
        stopStatus()
        super.onStop()
    }

    // ------------------------------------------------------------------ widgets

    private lateinit var layer: WidgetLayer
    private lateinit var root: android.widget.FrameLayout
    /** The widget picker while it is open, and the page's turn on the screen it follows. */
    private var picker: WidgetPicker? = null
    private var menu: WidgetMenu? = null
    private var toScreen = android.graphics.Matrix()

    /** Which widget of each slot's stack shows, and how many it has. */
    private val stackIndex = HashMap<String, Int>()
    private val stackCount = HashMap<String, Int>()

    /**
     * Every saved widget as a view in [layer], a slot's stack as one rounded frame holding all
     * of its widgets with one showing. A system widget saved before StandBy went back to its own
     * ([HiWidget]) leaves its stack, and the system's bindings for them are let go.
     */
    private fun loadWidgets() {
        layer.removeAllViews()
        stackCount.clear()
        stackAnim?.cancel()
        stackPos.clear()
        val views = LinkedHashMap<String, android.view.View>()
        val dens = resources.displayMetrics.density
        val pages = Prefs.pageIds(this)
        view.pageIds = pages
        for (p in pages) for (slot in StandbyView.SLOTS) {
            val key = StandbyView.slotKey(p, slot)
            val saved = Prefs.stack(this, key)
            if (saved.isEmpty()) continue
            val ids = saved.filter(HiWidget::isKnown)
            if (ids != saved) {
                Prefs.setStack(this, key, ids)
                AppWidgetHost(this, HOST_ID).deleteHost()
            }
            if (ids.isEmpty()) continue
            // Laid out at a home screen widget's size; StandbyView scales it to the slot.
            val wDp = if (slot == StandbyView.WIDE) WidgetSizes.WIDE_DP else WidgetSizes.SQUARE_DP
            val hDp = WidgetSizes.SQUARE_DP
            // Rounded like the slot it sits in.
            val frame = StackFrame(this).apply {
                tag = key
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    (wDp * dens).toInt(), (hDp * dens).toInt(),
                    android.view.Gravity.TOP or android.view.Gravity.START)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(o: android.view.View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, o.width, o.height, o.height * StandbyView.CORNER)
                    }
                }
                clipToOutline = true
                // A light frosted well, as iPhone's: what shows between a stack's widgets as they
                // scroll, so the music's blurred colours behind do not take over the slot.
                if (ids.size > 1) setBackgroundColor(if (key == managedKey) MANAGED_WELL else STACK_WELL)
                visibility = android.view.View.INVISIBLE
            }
            val shown = (stackIndex[key] ?: 0).coerceIn(0, ids.size - 1)
            stackIndex[key] = shown
            for ((i, id) in ids.withIndex()) {
                val v = HiWidget(this, id).apply {
                    city = WidgetCities.named(Prefs.city(this@StandbyActivity, key, id))
                    dark = Prefs.dark(this@StandbyActivity, key, id)
                    glass = Prefs.widgetGlass(this@StandbyActivity)
                    // Each its own rounded card, so a stack's widgets stay cards as they scroll.
                    outlineProvider = frame.outlineProvider
                    clipToOutline = true
                }
                v.visibility = if (i == shown) android.view.View.VISIBLE else android.view.View.INVISIBLE
                frame.addView(v, android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT))
            }
            stackCount[key] = ids.size
            layer.addView(frame)
            views[key] = frame
        }
        view.hosted = views
        // Rebuilt while the stack's editor is open (a widget went): it stays open on the new frame.
        managedKey?.let { key ->
            val frame = views[key] as? StackFrame
            if (frame == null || frame.childCount < 2) return@let closeManager()
            frame.post { layStack(key, frame, (stackIndex[key] ?: 0).toFloat()) }
        }
    }

    /** The widget showing in a slot's stack, gone for good; the slot is empty after the last. */
    private fun removeShown(key: String) {
        val ids = Prefs.stack(this, key).toMutableList()
        if (ids.isEmpty()) return
        val i = (stackIndex[key] ?: 0).coerceIn(0, ids.size - 1)
        ids.removeAt(i)
        Prefs.setStack(this, key, ids)
        stackIndex[key] = (i - 1).coerceAtLeast(0)
        loadWidgets()
    }

    /** A slot's widgets put on another: on top of its stack, the dropped one showing. */
    private fun drop(from: String, to: String) {
        val moving = Prefs.stack(this, from)
        val there = Prefs.stack(this, to)
        Prefs.setStack(this, to, there + moving)
        Prefs.setStack(this, from, emptyList())
        stackIndex[to] = there.size + (stackIndex[from] ?: 0)
        stackIndex.remove(from)
        loadWidgets()
    }

    /** A page and every widget on it, gone for good. */
    private fun deletePage(id: Int) {
        for (slot in StandbyView.SLOTS) {
            val key = StandbyView.slotKey(id, slot)
            Prefs.setStack(this, key, emptyList())
            stackIndex.remove(key)
        }
        Prefs.setPageIds(this, Prefs.pageIds(this) - id)
        loadWidgets()
    }

    /** Where each stack stands while it moves (a widget index, fractional), and its settle. */
    private val stackPos = HashMap<String, Float>()
    private var stackFrom = Float.NaN
    private var stackAnim: android.animation.ValueAnimator? = null

    /**
     * A stack following the finger, as iPhone's StandBy: its widgets stand one above the other
     * in the slot's rounded frame, a little apart, and scroll up and down with the finger - the
     * one leaving shrinks as it goes out of the frame, the one coming grows as it comes in. Let
     * go past a fifth of the way, it settles on the next one, else back; held at either end.
     */
    private fun scrollStack(key: String, dy: Float, done: Boolean) {
        val frame = view.hosted[key] as? android.view.ViewGroup ?: return
        val n = frame.childCount
        val step = frame.height * stepOf(key) * frame.scaleY
        if (n < 2 || step <= 0f) return
        view.showStackDots(key)
        if (stackAnim?.isRunning != true && !stackPos.containsKey(key)) {
            stackPos[key] = (stackIndex[key] ?: 0).toFloat()
        }
        if (stackFrom.isNaN()) {
            stackAnim?.cancel()
            stackFrom = stackPos[key] ?: 0f
        }
        var pos = stackFrom - dy / step
        // Past either end it only gives a little.
        if (pos < 0f) pos *= 0.3f
        if (pos > n - 1f) pos = n - 1f + (pos - (n - 1f)) * 0.3f
        if (!done) {
            layStack(key, frame, pos)
            return
        }
        val moved = pos - stackFrom
        val target = when {
            moved > 0.2f -> kotlin.math.floor(stackFrom) + 1f
            moved < -0.2f -> kotlin.math.ceil(stackFrom) - 1f
            else -> Math.round(stackFrom).toFloat()
        }.coerceIn(0f, n - 1f)
        stackIndex[key] = target.toInt()
        stackFrom = Float.NaN
        stackAnim = android.animation.ValueAnimator.ofFloat(pos, target).apply {
            duration = STACK_MS
            interpolator = STACK_EASE
            addUpdateListener { layStack(key, frame, it.animatedValue as Float) }
            start()
        }
    }

    /** How far apart a stack's widgets stand, in its frame's heights: closer while managed. */
    private fun stepOf(key: String): Float {
        val m = if (key == managedKey) managedT else 0f
        return 1f + STACK_GAP + (MANAGED_STEP - 1f - STACK_GAP) * m
    }

    /**
     * The stack's widgets placed for a scroll position [pos]; the ones out of sight hidden. In
     * the stack's editor they are all smaller and the ones before and after peek in.
     */
    private fun layStack(key: String, frame: android.view.ViewGroup, pos: Float) {
        stackPos[key] = pos
        view.showStackDots(key)
        val m = if (key == managedKey) managedT else 0f
        (frame as? StackFrame)?.managed = m
        val h = frame.height.toFloat()
        val step = stepOf(key)
        val reach = 1f + STACK_GAP + (MANAGED_REACH - 1f - STACK_GAP) * m
        val base = 1f - (1f - MANAGED_SCALE) * m
        val shrink = STACK_SHRINK * (1f - m)
        for (i in 0 until frame.childCount) {
            val v = frame.getChildAt(i)
            val d = i - pos
            val far = kotlin.math.abs(d)
            if (far >= reach) {
                v.visibility = android.view.View.INVISIBLE
                continue
            }
            v.visibility = android.view.View.VISIBLE
            v.alpha = 1f
            v.translationY = d * h * step
            val s = base * (1f - shrink * far.coerceAtMost(1f))
            v.scaleX = s
            v.scaleY = s
        }
    }

    // ------------------------------------------------------------------ the stack's editor

    /** The stack being managed in place, how far its editor is open, and what takes its touches. */
    private var managedKey: String? = null
    private var managedT = 0f
    private var managedBox = android.graphics.RectF()
    private var managedAnim: android.animation.ValueAnimator? = null
    private var manager: android.view.View? = null

    /**
     * The held stack's editor, as iPhone's, right where the stack is: its menu goes but the rest
     * stays blurred, the frame darkens, and its widgets shrink so the ones before and after peek
     * in, each with its name and a "-". Scrolled up and down; "-" takes that widget out of the
     * stack; a tap off the stack closes it.
     */
    private fun manageStack(key: String, box: android.graphics.RectF) {
        val frame = view.hosted[key] as? StackFrame ?: return
        if (frame.childCount < 2 || managedKey != null) return
        managedKey = key
        managedBox = android.graphics.RectF(box)
        menu?.close()
        frame.setBackgroundColor(MANAGED_WELL)
        val pos = stackPos[key] ?: (stackIndex[key] ?: 0).toFloat()
        animateManaged(1f) { layStack(key, frame, pos) }
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val overlay = android.view.View(this)
        var downX = 0f
        var downY = 0f
        var dragging = false
        overlay.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    dragging = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging && kotlin.math.abs(e.y - downY) > slop) dragging = true
                    if (dragging) scrollStack(key, e.y - downY, false)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragging) scrollStack(key, e.y - downY, true)
                    else if (e.actionMasked == android.view.MotionEvent.ACTION_UP) managerTap(key, e.x, e.y)
                }
            }
            true
        }
        overlay.layoutParams = android.widget.FrameLayout.LayoutParams(root.height, root.width)
        manager = overlay
        root.addView(overlay)
        turn(overlay)
    }

    /** A tap in the editor: on a widget's "-" that widget leaves the stack; off the stack, it closes. */
    private fun managerTap(key: String, x: Float, y: Float) {
        val frame = view.hosted[key] as? StackFrame ?: return closeManager()
        val box = managedBox
        val k = box.height() / frame.height.coerceAtLeast(1)
        val hit = 22f * resources.displayMetrics.density
        for (i in 0 until frame.childCount) {
            if (frame.getChildAt(i).visibility != android.view.View.VISIBLE) continue
            val (bx, by, _) = frame.badgeOf(i)
            if (kotlin.math.hypot(box.left + bx * k - x, box.top + by * k - y) < hit) {
                val ids = Prefs.stack(this, key).toMutableList()
                if (i !in ids.indices) return
                ids.removeAt(i)
                Prefs.setStack(this, key, ids)
                stackIndex[key] = (stackIndex[key] ?: 0).coerceIn(0, (ids.size - 1).coerceAtLeast(0))
                loadWidgets()
                return
            }
        }
        if (!box.contains(x, y)) closeManager()
    }

    /** The editor closes: the widgets grow back, the frame lightens, and the held stack goes down. */
    private fun closeManager() {
        val key = managedKey ?: return
        manager?.let { root.removeView(it) }
        manager = null
        val frame = view.hosted[key] as? StackFrame
        val pos = Math.round(stackPos[key] ?: (stackIndex[key] ?: 0).toFloat()).toFloat()
        frame?.let { stackIndex[key] = pos.toInt().coerceIn(0, it.childCount - 1) }
        animateManaged(0f, {
            managedKey = null
            frame?.setBackgroundColor(STACK_WELL)
            frame?.managed = 0f
            view.closeMenu()
        }) { frame?.let { layStack(key, it, pos) } }
    }

    private fun animateManaged(to: Float, end: (() -> Unit)? = null, each: () -> Unit) {
        managedAnim?.cancel()
        managedAnim = android.animation.ValueAnimator.ofFloat(managedT, to).apply {
            duration = STACK_MS
            interpolator = STACK_EASE
            addUpdateListener {
                managedT = it.animatedValue as Float
                each()
            }
            if (end != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) = end()
            })
            start()
        }
    }

    /**
     * Our own picker ([WidgetPicker]), over the page and turned with it. A whole slot offers 2x2
     * and 4x2 widgets, a half only 2x2; a 2x2 one picked for a whole slot takes its left half,
     * and the right half becomes a slot of its own. [replace]: the slot's shown widget changes
     * to the one picked.
     */
    private fun pick(slot: String, replace: Boolean) {
        if (picker != null) return
        val whole = StandbyView.slotOf(slot) == StandbyView.WIDE
        val p = WidgetPicker(this, whole, replace, onPick = { kind ->
            if (replace) return@WidgetPicker replaceShown(slot, kind.id)
            val target = if (whole && !kind.wide) {
                StandbyView.slotKey(StandbyView.pageOf(slot) ?: return@WidgetPicker, 0)
            } else slot
            place(target, kind.id)
            view.landWidget(target)
        }, onClose = {
            root.removeView(picker)
            picker = null
        })
        // Laid out landscape, the page's own width and height, and turned onto the screen.
        p.layoutParams = android.widget.FrameLayout.LayoutParams(root.height, root.width)
        picker = p
        root.addView(p)
        turn(p)
    }

    /** A held widget's menu, beside it; when it goes, StandbyView lets the widget down. */
    private fun showMenu(key: String, box: android.graphics.RectF) {
        menu?.let { root.removeView(it) }
        lateinit var m: WidgetMenu
        m = WidgetMenu(this, box, root.height, root.width,
            onChange = { pick(key, replace = true) },
            onRemove = { removeShown(key) },
            onBoxTap = { manageStack(key, box) },
            onClose = {
                root.removeView(m)
                if (menu === m) menu = null
                // Into the stack's editor the widget stays up, and the rest blurred.
                if (managedKey == null && settings == null) view.closeMenu()
            },
            onSettings = shownKind(key)?.let { kind -> { openSettings(key, kind, box) } },
            widget = view.hosted[key],
            widgetDark = shownKind(key)?.let { Prefs.dark(this, key, it) } ?: true)
        m.layoutParams = android.widget.FrameLayout.LayoutParams(root.height, root.width)
        menu = m
        root.addView(m)
        turn(m)
    }

    private var settings: WidgetSettings? = null

    private fun shownKind(key: String): Int? {
        val ids = Prefs.stack(this, key)
        return ids.getOrNull((stackIndex[key] ?: 0).coerceIn(0, (ids.size - 1).coerceAtLeast(0)))
    }

    /** The held widget's settings, over it; the widget stays up, and the rest blurred, until they go. */
    private fun openSettings(key: String, id: Int, box: android.graphics.RectF) {
        val kind = HiWidget.ALL.firstOrNull { it.id == id } ?: return
        lateinit var s: WidgetSettings
        val shown = { (view.hosted[key] as? android.view.ViewGroup)?.getChildAt(stackIndex[key] ?: 0) as? HiWidget }
        s = WidgetSettings(this, kind, box, WidgetCities.named(Prefs.city(this, key, id)), Prefs.dark(this, key, id),
            onPick = { city ->
                Prefs.setCity(this, key, id, city?.name)
                shown()?.city = city
            },
            onDark = { on ->
                Prefs.setDark(this, key, id, on)
                shown()?.dark = on
            },
            onShown = { view.coveredKey = key },
            onClosing = { view.coveredKey = null },
            onClose = {
                root.removeView(s)
                settings = null
                view.closeMenu()
            })
        s.layoutParams = android.widget.FrameLayout.LayoutParams(root.height, root.width)
        settings = s
        menu?.close()
        root.addView(s)
        turn(s)
    }

    /** The widget showing in a slot's stack, changed to [id]. */
    private fun replaceShown(key: String, id: Int) {
        val ids = Prefs.stack(this, key).toMutableList()
        if (ids.isEmpty()) return place(key, id)
        ids[(stackIndex[key] ?: 0).coerceIn(0, ids.size - 1)] = id
        Prefs.setStack(this, key, ids)
        loadWidgets()
    }

    /** [v], laid out in page coordinates, put where the page is: as the hosted widgets are. */
    private fun turn(v: android.view.View) {
        val vals = FloatArray(9).also { toScreen.getValues(it) }
        v.pivotX = 0f
        v.pivotY = 0f
        v.translationX = vals[android.graphics.Matrix.MTRANS_X]
        v.translationY = vals[android.graphics.Matrix.MTRANS_Y]
        v.rotation = Math.toDegrees(kotlin.math.atan2(vals[android.graphics.Matrix.MSKEW_Y].toDouble(),
            vals[android.graphics.Matrix.MSCALE_X].toDouble())).toFloat()
    }

    private fun place(slot: String, id: Int) {
        Prefs.setStack(this, slot, Prefs.stack(this, slot) + id)
        stackIndex[slot] = Prefs.stack(this, slot).size - 1
        loadWidgets()
    }

    /**
     * The volume keys change the music's volume here without HyperOS's panel; StandBy shows its
     * own bar instead ([StandbyView.showVolume]). Held, the key repeats.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val up = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
        if (!up && keyCode != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyDown(keyCode, event)
        val am = getSystemService(android.media.AudioManager::class.java)
        val stream = android.media.AudioManager.STREAM_MUSIC
        val before = am.getStreamVolume(stream)
        am.adjustStreamVolume(stream, if (up) android.media.AudioManager.ADJUST_RAISE
            else android.media.AudioManager.ADJUST_LOWER, 0)
        val after = am.getStreamVolume(stream)
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        view.showVolume(after / max.toFloat(), if (after != before) 0 else if (up) 1 else -1)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode != android.view.KeyEvent.KEYCODE_VOLUME_UP &&
            keyCode != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyUp(keyCode, event)
        view.releaseVolume()
        return true
    }

    override fun onPause() {
        getSystemService(android.hardware.SensorManager::class.java).unregisterListener(sideListener)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(close)
        unregisterReceiver(screenOff)
        super.onDestroy()
    }


    /** The power key: left standing, StandBy's turn-screen-on could wake the phone again. */
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) = goAway()
    }

    /** Closed at once, with nothing left to bring it back until the phone has been upright. */
    private fun goAway() {
        StandbyService.hold(this)
        leaving = true
        finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    private fun leave() {
        if (leaving) return
        leaving = true
        view.leave {
            finish()
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }

    companion object {
        const val ACTION_CLOSE = "com.os4.immersive.STANDBY_CLOSE"
        private const val DRIFT_MS = 60_000L
        private const val DRIFT_MAX_PX = 3
        const val EXTRA_SIDE = "side"
        /** The AppWidgetHost the system widgets were kept under, let go of. */
        private const val HOST_ID = 0x5742
        /** A stack's swipe from one widget to the next. */
        /** A stack's settle; its widgets' gap and how small one gets at a whole step away. */
        private const val STACK_MS = 380L
        private const val STACK_GAP = 0.08f
        private const val STACK_SHRINK = 0.14f
        /** In the stack's editor: its widgets' size, their spacing and how far out they still show. */
        private const val MANAGED_SCALE = 0.56f
        private const val MANAGED_STEP = 0.68f
        private const val MANAGED_REACH = 1.9f
        /** A stack's frame: light frost behind its widgets, darker in its editor. */
        private val STACK_WELL = android.graphics.Color.argb(150, 128, 128, 134)
        private val MANAGED_WELL = android.graphics.Color.argb(200, 22, 22, 24)
        private val STACK_EASE = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    }
}
