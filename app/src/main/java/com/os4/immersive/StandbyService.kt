package com.os4.immersive

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Prototype watcher. While the phone charges it listens to the accelerometer and runs
 * [StandbyDetector]; the moment it says ACTIVE, [StandbyActivity] opens over the lock screen,
 * and it closes again when the detector lets go.
 *
 * A foreground service only because this prototype is a plain app: the extension proper will
 * watch from SystemUI, which is always running and may start activities on the lock screen.
 * Nothing is sampled while the phone is not charging.
 */
class StandbyService : Service(), SensorEventListener {

    private lateinit var sensors: SensorManager
    private var accel: Sensor? = null
    private var listening = false
    private var charging = false
    private var wake: PowerManager.WakeLock? = null
    private val detector = StandbyDetector()
    private var lastState = StandbyDetector.State.IDLE
    private var lastX = 0f

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (plugged != charging) {
                charging = plugged
                trace("charging=$charging")
                if (!charging) {
                    release()
                    detector.unplugged()
                    onState(StandbyDetector.State.IDLE)
                }
                updateListening()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(SensorManager::class.java)
        accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        startForeground(WATCHER_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        heldOff = Prefs.heldOff(this)
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        running = true
        // Ask for the artwork listener to be bound again: after an update it can stay unbound.
        runCatching {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(this, MediaListener::class.java))
        }
        trace("watching")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        unregisterReceiver(battery)
        charging = false
        updateListening()
        Debug.publish("stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun release() {
        heldOff = false
        Prefs.setHeldOff(this, false)
    }

    private fun updateListening() {
        val want = charging && accel != null
        if (want == listening) return
        listening = want
        if (want) {
            // The CPU must stay up to keep hearing the sensor with the screen off; cheap on a
            // charger, and released the moment the charger goes.
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyperImmersive:standby")
                .apply { acquire() }
            sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
        } else {
            sensors.unregisterListener(this)
            wake?.let { if (it.isHeld) it.release() }
            wake = null
            detector.reset()
        }
        Debug.publish(if (want) "listening" else "idle (not charging)")
    }

    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.uptimeMillis()
        lastX = e.values[0]
        val s = detector.update(now, charging, e.values[0], e.values[1], e.values[2])
        // Sent away: only upright for a while may StandBy come back - not a phone still on its
        // side, nudged or woken by the power key.
        if (heldOff) {
            if (!StandbyDetector.isPortrait(e.values[0], e.values[1])) uprightSince = 0L
            else if (uprightSince == 0L) uprightSince = now
            else if (now - uprightSince > UPRIGHT_MS) {
                release()
                trace("upright again, StandBy may come back")
            }
        }
        // Live readings only while the app's screen shows them: this runs all night on a charger.
        if (Debug.listener != null) Debug.publish("state=$s ${(detector.progress * 100).toInt()}% landscape=${detector.landscape} " +
            "still=${detector.still}\nx=%.1f y=%.1f z=%.1f".format(e.values[0], e.values[1], e.values[2]))
        onState(s)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onState(s: StandbyDetector.State) {
        if (s == lastState) return
        val was = lastState
        lastState = s
        trace("$was -> $s")
        if (s == StandbyDetector.State.ACTIVE) {
            // HMC-E can go while this runs, and START_STICKY brings the watcher back without Bridge.watch.
            if (!Bridge.hmcReady(this)) {
                trace("HMC-E is gone, the watcher stops")
                stopSelf()
                return
            }
            if (heldOff) {
                trace("sent away, StandBy stays away until the phone is upright")
                return
            }
            val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
            // Like iPhone, StandBy only ever takes over a locked phone.
            if (!locked) {
                trace("unlocked, StandBy waits for the lock screen")
                return
            }
            launch()
        } else if (was == StandbyDetector.State.ACTIVE) {
            sendBroadcast(Intent(StandbyActivity.ACTION_CLOSE).setPackage(packageName))
        }
    }

    /**
     * A plain startActivity from a service is dropped without a word on the lock screen. A full-screen intent is how an
     * alarm takes the lock screen, and it is what opens StandBy here; the activity takes the
     * notification down as soon as it is up. The direct start stays for the unlocked case.
     */
    private fun launch() {
        // HyperOS can leave an allowed listener unbound (an update, a denied autostart): ask
        // again each time StandBy opens, so the music page is not left saying Not Playing.
        if (!MediaArt.connected) {
            trace("listener unbound, asking for a rebind")
            runCatching {
                android.service.notification.NotificationListenerService.requestRebind(
                    android.content.ComponentName(this, MediaListener::class.java))
            }
        }
        val intent = Intent(this, StandbyActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(StandbyActivity.EXTRA_SIDE, if (lastX >= 0f) 1 else -1)
        runCatching { startActivity(intent) }.onFailure { trace("direct start failed: $it") }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SHOW, "StandBy",
            NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val pi = android.app.PendingIntent.getActivity(this, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(SHOW_ID, Notification.Builder(this, CHANNEL_SHOW)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("StandBy")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setTimeoutAfter(10_000)
            .build())
        trace("StandBy launched (fsi=${nm.canUseFullScreenIntent()})")
    }

    private fun trace(msg: String) {
        Log.i(TAG, msg)
        runCatching {
            val f = java.io.File(filesDir, "trace.txt")
            if (f.length() > 200_000) f.delete()
            f.appendText(java.time.LocalTime.now().toString().take(12) + " " + msg + "\n")
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        // The system lifts this to low for a foreground service; Hooks is what actually hides it.
        // The old channel goes first; it cannot be lowered once the user's settings have touched it.
        nm.deleteNotificationChannel("watcher")
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "StandBy watcher",
            NotificationManager.IMPORTANCE_NONE))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("StandBy")
            .setContentText("Watching while charging")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val TAG = "HyperImmersive"
        private const val CHANNEL = "watcher_hidden"
        private const val CHANNEL_SHOW = "standby"
        const val WATCHER_ID = 1
        const val SHOW_ID = 2
        private const val UPRIGHT_MS = 1_500L
        @Volatile var running = false
        /** StandBy was sent away: its emergency way out, the power key, its turn-off delay; see onSensorChanged. */
        @Volatile var heldOff = false

        /** Kept across a restart of the watcher, which would otherwise bring StandBy straight back. */
        fun hold(c: Context) {
            heldOff = true
            Prefs.setHeldOff(c, true)
        }
        private var uprightSince = 0L
    }
}

/** The service's last word, for the prototype's own screen. */
object Debug {
    @Volatile var text = "stopped"
        private set
    @Volatile var listener: ((String) -> Unit)? = null
    fun publish(s: String) {
        text = s
        listener?.invoke(s)
    }
}
