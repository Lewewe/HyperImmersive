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

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (plugged != charging) {
                charging = plugged
                Log.i(TAG, "charging=$charging")
                if (!charging) {
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
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        running = true
        Log.i(TAG, "watching")
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
        val s = detector.update(now, charging, e.values[0], e.values[1], e.values[2])
        Debug.publish("state=$s ${(detector.progress * 100).toInt()}% landscape=${detector.landscape} " +
            "still=${detector.still}\nx=%.1f y=%.1f z=%.1f".format(e.values[0], e.values[1], e.values[2]))
        onState(s)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onState(s: StandbyDetector.State) {
        if (s == lastState) return
        val was = lastState
        lastState = s
        Log.i(TAG, "$was -> $s")
        if (s == StandbyDetector.State.ACTIVE) {
            val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
            if (lockedOnly && !locked) {
                Log.i(TAG, "unlocked, StandBy waits for the lock screen")
                return
            }
            startActivity(Intent(this, StandbyActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } else if (was == StandbyDetector.State.ACTIVE) {
            sendBroadcast(Intent(StandbyActivity.ACTION_CLOSE).setPackage(packageName))
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "StandBy watcher",
            NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("StandBy prototype")
            .setContentText("Watching while charging")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val TAG = "HyperImmersive"
        private const val CHANNEL = "watcher"
        @Volatile var running = false
        /** Like iPhone, StandBy only takes over a locked phone. Off for testing on the desk. */
        @Volatile var lockedOnly = true
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
