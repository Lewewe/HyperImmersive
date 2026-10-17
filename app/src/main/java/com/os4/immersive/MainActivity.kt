package com.os4.immersive

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

/** Prototype controls: start/stop the watcher, preview StandBy, live detector readout. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        fun button(label: String, onClick: () -> Unit) = root.addView(Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        })
        root.addView(TextView(this).apply {
            text = "HyperImmersive · StandBy prototype"
            textSize = 20f
        })
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, pad)
            gravity = Gravity.START
        }
        root.addView(status)
        button("Start watching") {
            startForegroundService(Intent(this, StandbyService::class.java))
            refresh()
        }
        button("Stop watching") {
            stopService(Intent(this, StandbyService::class.java))
            refresh()
        }
        button("Preview StandBy now") {
            startActivity(Intent(this, StandbyActivity::class.java))
        }
        root.addView(CheckBox(this).apply {
            text = "Only on the lock screen (like iPhone)"
            isChecked = StandbyService.lockedOnly
            setOnCheckedChangeListener { _, on -> StandbyService.lockedOnly = on }
        })
        button("Allow opening from background") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        setContentView(root)
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != 0) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Debug.listener = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        Debug.listener = null
        super.onPause()
    }

    private fun refresh() {
        status.text = "Watcher: " + (if (StandbyService.running) "on" else "off") +
            "\nBackground start: " + (if (Settings.canDrawOverlays(this)) "allowed" else "NOT allowed") +
            "\n\n" + Debug.text
    }
}
