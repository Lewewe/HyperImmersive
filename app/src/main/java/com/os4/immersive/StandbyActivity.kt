package com.os4.immersive

import android.app.Activity
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
        setContentView(view)
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        registerReceiver(close, IntentFilter(ACTION_CLOSE), RECEIVER_NOT_EXPORTED)
        getSystemService(android.app.NotificationManager::class.java).cancel(StandbyService.SHOW_ID)
        view.post { view.enter() }
    }

    override fun onDestroy() {
        unregisterReceiver(close)
        super.onDestroy()
    }

    @Deprecated("Back runs the leave animation first")
    override fun onBackPressed() = leave()

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
    }
}
