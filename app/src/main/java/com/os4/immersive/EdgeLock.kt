package com.os4.immersive

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * While StandBy holds the lock screen the system's edge gestures do nothing and show nothing,
 * as on iPhone: StandBy owns the screen until the phone is picked up.
 *
 * The app ([StandbyActivity]) repeats [ACTION] with `lock` every [REPEAT_MS] to SystemUI, where
 * [Hooks] has this listening. It turns home, recents, back, the shade and the status bar's
 * contents off through the status bar service, and makes its edge windows see-through, since a
 * swipe still brings the hidden bars in for a moment (window manager does that, not the flags).
 * A repeat without `lock`, or none for [EXPIRE_MS] - the app died - gives everything back.
 */
object EdgeLock {
    const val ACTION = "com.os4.immersive.EDGE_LOCK"
    const val SYSTEMUI = "com.android.systemui"
    const val PING = "ping"
    const val LOADED = 1
    const val REPEAT_MS = 1_000L
    private const val EXPIRE_MS = 3_000L
    const val APP = "com.yzc26623.HyperImmersive"
    private const val TAG = "HyperImmersive"

    // StatusBarManager.DISABLE_EXPAND, _NOTIFICATION_ICONS, _SYSTEM_INFO, _HOME, _BACK, _CLOCK, _RECENT.
    private const val FLAGS = 0x00010000 or 0x00020000 or 0x00100000 or 0x00200000 or
        0x00400000 or 0x00800000 or 0x01000000
    // StatusBarManager.DISABLE2_QUICK_SETTINGS, _SYSTEM_ICONS, _NOTIFICATION_SHADE.
    private const val FLAGS2 = 0x1 or 0x2 or 0x4
    /** SystemUI's edge windows. */
    private val WINDOWS = setOf("StatusBar", "NavigationBar0", "DynamicIslandWindow")

    // ------------------------------------------------------------------ app side

    /** From the app: [lock] for the next [EXPIRE_MS], or off now. */
    fun send(c: Context, lock: Boolean) {
        runCatching {
            c.sendBroadcast(Intent(ACTION).setPackage(SYSTEMUI).putExtra("lock", lock), null,
                android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        }
    }

    /** From the app: the screen off, skipping the always-on display. */
    fun sleep(c: Context) {
        runCatching {
            c.sendBroadcast(Intent(ACTION).setPackage(SYSTEMUI).putExtra("sleep", true), null,
                android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        }
    }

    // ------------------------------------------------------------------ hooked side

    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** SystemUI's own StatusBarManager shares one token with the keyguard's flags: not that one. */
    private val token = Binder()
    private var on = false
    private val expire = Runnable { set(false) }

    fun listen(app: Application) {
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                // Bridge asking whether this hook is loaded: says nothing and does nothing else.
                if (i.getBooleanExtra(PING, false)) {
                    resultCode = LOADED
                    return
                }
                if (sentFromPackage != APP) return
                if (i.getBooleanExtra("sleep", false)) {
                    goToSleep(c)
                    return
                }
                val lock = i.getBooleanExtra("lock", false)
                main.removeCallbacks(expire)
                if (lock) main.postDelayed(expire, EXPIRE_MS)
                set(lock)
            }
        }, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
    }

    /** SystemUI may: it holds DEVICE_POWER. PowerManager.goToSleep is hidden; flag 1 is GO_TO_SLEEP_FLAG_NO_DOZE. */
    private fun goToSleep(c: Context) = runCatching {
        c.getSystemService(android.os.PowerManager::class.java).javaClass
            .getMethod("goToSleep", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(c.getSystemService(android.os.PowerManager::class.java), android.os.SystemClock.uptimeMillis(), 0, 1)
    }.onFailure { Log.w(TAG, "screen not turned off: $it") }

    private fun set(lock: Boolean) {
        // Again with each repeat: a window rebuilt in between comes back hidden.
        if (lock) hideWindows(true)
        if (lock == on) return
        on = lock
        if (!lock) hideWindows(false)
        disable(lock)
    }

    private fun disable(lock: Boolean) = runCatching {
        val service = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "statusbar") as IBinder
        val bar = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, service)!!
        val types = arrayOf(Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
        bar.javaClass.getMethod("disable", *types).invoke(bar, if (lock) FLAGS else 0, token, SYSTEMUI)
        bar.javaClass.getMethod("disable2", *types).invoke(bar, if (lock) FLAGS2 else 0, token, SYSTEMUI)
    }.onFailure { Log.w(TAG, "edge gestures not disabled: $it") }

    private fun hideWindows(hide: Boolean) = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val wmg = global.getMethod("getInstance").invoke(null)
        val roots = global.getDeclaredField("mRoots").apply { isAccessible = true }.get(wmg) as List<*>
        for (root in roots.toList()) {
            val view = root?.javaClass?.getMethod("getView")?.invoke(root) as? View ?: continue
            val title = (view.layoutParams as? WindowManager.LayoutParams)?.title?.toString()
            if (title in WINDOWS) view.alpha = if (hide) 0f else 1f
        }
    }.onFailure { Log.w(TAG, "edge windows not hidden: $it") }
}
