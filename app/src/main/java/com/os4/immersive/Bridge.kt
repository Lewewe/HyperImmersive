package com.os4.immersive

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * HyperImmersive has no screen of its own: it is an extension of HyperMusicCover-Enhanced, whose
 * Extensions page reads and sets it through [call] (as it does HyperCanvas). Only HMC-E, signed
 * like this app, is answered.
 * - `query`: its state, see [state]; also starts the watcher if it should be running.
 * - `configure`: any of `enabled` (the watcher), `blurBackdrop`, `idleSeconds`, `offSeconds`; returns the state.
 * - `preview`: StandBy now, whatever the phone is doing.
 */
class Bridge : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val c = context ?: return null
        val caller = callingPackage
        if (caller != HMC || c.packageManager.checkSignatures(caller, c.packageName) !=
            PackageManager.SIGNATURE_MATCH) return null
        when (method) {
            "configure" -> extras?.let { e ->
                if (e.containsKey("blurBackdrop")) Prefs.setBlurBackdrop(c, e.getBoolean("blurBackdrop"))
                if (e.containsKey("lyricsBeside")) Prefs.setLyricsBeside(c, e.getBoolean("lyricsBeside"))
                if (e.containsKey("lyricsStyle")) Prefs.setLyricsStyle(c, e.getInt("lyricsStyle"))
                if (e.containsKey("idleSeconds")) Prefs.setIdleSeconds(c, e.getInt("idleSeconds"))
                if (e.containsKey("offSeconds")) Prefs.setOffSeconds(c, e.getInt("offSeconds"))
                if (e.containsKey("enabled")) {
                    Prefs.setEnabled(c, e.getBoolean("enabled"))
                    watch(c)
                }
            }
            "preview" -> preview(c)
            // After an install nothing has started the watcher yet, and the boot receiver may not fire.
            "query" -> if (!StandbyService.running) watch(c)
            else -> return null
        }
        return state(c)
    }

    /** Whether SystemUI has [Hooks] loaded; null when it did not answer in time. */
    private fun hookActive(c: Context): Boolean? {
        val done = java.util.concurrent.CountDownLatch(1)
        var active = false
        c.sendOrderedBroadcast(Intent(EdgeLock.ACTION).setPackage(EdgeLock.SYSTEMUI)
            .putExtra(EdgeLock.PING, true).addFlags(Intent.FLAG_RECEIVER_FOREGROUND), null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    active = resultCode == EdgeLock.LOADED
                    done.countDown()
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()), 0, null, null)
        return if (done.await(HOOK_ANSWER_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) active else null
    }

    private fun state(c: Context) = Bundle().apply {
        putBoolean("installed", true)
        hookActive(c)?.let { putBoolean("hookActive", it) }
        putString("version", runCatching {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName
        }.getOrNull())
        putBoolean("enabled", Prefs.enabled(c))
        putBoolean("watching", StandbyService.running)
        putBoolean("blurBackdrop", Prefs.blurBackdrop(c))
        putBoolean("lyricsBeside", Prefs.lyricsBeside(c))
        putInt("lyricsStyle", Prefs.lyricsStyle(c))
        putInt("idleSeconds", Prefs.idleSeconds(c))
        putInt("offSeconds", Prefs.offSeconds(c))
        putBoolean("notificationAccess", c.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(c, MediaListener::class.java)))
        putBoolean("backgroundStart", Settings.canDrawOverlays(c))
        putBoolean("notifications", c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED)
        putBoolean("lockScreen", c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent())
        putBoolean("calendar", c.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED)
    }

    /** Android 15 only lets an app start activities from the background while an overlay of its is visible. */
    private fun preview(c: Context) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            val wm = c.getSystemService(android.view.WindowManager::class.java)
            val anchor = android.view.View(c)
            runCatching {
                wm.addView(anchor, android.view.WindowManager.LayoutParams(1, 1,
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    android.graphics.PixelFormat.TRANSLUCENT))
            }
            main.postDelayed({
                runCatching { c.startActivity(Intent(c, StandbyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, PREVIEW_ANCHOR_MS)
            main.postDelayed({ runCatching { wm.removeView(anchor) } }, PREVIEW_ANCHOR_MS * 5)
        }
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        private const val HMC = "com.yzc26623.HyperMusicCoverEnhanced"
        private const val PREVIEW_ANCHOR_MS = 200L
        private const val HOOK_ANSWER_MS = 1_500L

        /** HI is an extension: without HMC-E, signed like this app, it does not run. */
        fun hmcReady(c: Context) = runCatching {
            c.packageManager.getPackageInfo(HMC, 0)
            c.packageManager.checkSignatures(HMC, c.packageName) == PackageManager.SIGNATURE_MATCH
        }.getOrDefault(false)

        /** The watcher as [Prefs.enabled] wants it, while HMC-E is there. */
        fun watch(c: Context) {
            val i = Intent(c, StandbyService::class.java)
            if (Prefs.enabled(c) && hmcReady(c)) runCatching { c.startForegroundService(i) } else c.stopService(i)
        }
    }
}

/** With no screen to start it from, the watcher comes back by itself after a reboot or an update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Bridge.watch(c)
        }
    }
}
