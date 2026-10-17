package com.os4.immersive

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The artwork of whatever is playing, for StandBy's backdrop.
 *
 * Read from the active media sessions, which a notification listener is allowed to see. A
 * session does not always carry a bitmap (YouTube Music often sends none), so the media
 * notification's large icon - the same artwork the player shows - stands in.
 *
 * In the extension proper this comes from HyperMusicCover-Enhanced, which already has the art;
 * the listener is the prototype's own way in.
 */
object MediaArt {
    @Volatile var art: Bitmap? = null
        private set
    @Volatile var playing = false
        private set
    /** Strictly playing, as opposed to paused with a track loaded. */
    @Volatile var running = false
        private set
    @Volatile var title = ""
        private set
    @Volatile var artist = ""
        private set
    /** Identifies the track: a new key is a new song, the same key a refreshed picture of it. */
    @Volatile var key = ""
        private set

    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var notificationArt: Bitmap? = null
    private var notificationPkg: String? = null

    fun addListener(l: () -> Unit) { listeners += l }

    fun playPause() {
        val t = controller?.transportControls ?: return
        if (running) t.pause() else t.play()
    }
    fun next() { lastSkip = 1; lastSkipAt = android.os.SystemClock.uptimeMillis(); controller?.transportControls?.skipToNext() }

    /** The last skip pressed here and when, so a track change can tell which way it went. */
    @Volatile var lastSkip = 1
        private set
    @Volatile var lastSkipAt = 0L
        private set
    fun seekTo(ms: Long) { controller?.transportControls?.seekTo(ms) }

    /** Track length in ms, or 0 when the player does not say. */
    @Volatile var duration = 0L
        private set
    @Volatile private var playback: PlaybackState? = null

    /**
     * Where the track is now. A player reports its position only when something changes, with
     * the time it was true and the speed it moves at; the rest is extrapolated from those.
     */
    fun positionNow(): Long {
        val s = playback ?: return 0L
        var p = s.position
        if (s.state == PlaybackState.STATE_PLAYING && s.lastPositionUpdateTime > 0) {
            p += ((android.os.SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) *
                s.playbackSpeed).toLong()
        }
        val d = duration
        return if (d > 0) p.coerceIn(0L, d) else p.coerceAtLeast(0L)
    }
    fun previous() { lastSkip = -1; lastSkipAt = android.os.SystemClock.uptimeMillis(); controller?.transportControls?.skipToPrevious() }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** Something is playing (or paused mid-track) and there is a picture of it. */
    val shown get() = playing && art != null

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list ?: emptyList())
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() = refresh()
    }

    internal fun connect(context: Context, listener: ComponentName) {
        component = listener
        val m = context.getSystemService(MediaSessionManager::class.java)
        manager = m
        runCatching {
            m.addOnActiveSessionsChangedListener(sessions, listener, main)
            pick(m.getActiveSessions(listener))
        }
    }

    internal fun disconnect() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessions) }
        controller?.unregisterCallback(callback)
        controller = null
        set(null, false)
    }

    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        refresh()
    }

    private fun refresh() {
        val c = controller
        val state = c?.playbackState?.state
        val active = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED ||
            state == PlaybackState.STATE_BUFFERING
        val md = c?.metadata
        playback = c?.playbackState
        duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
        val fromSession = md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        val fromNotification = notificationArt.takeIf { notificationPkg == c?.packageName }
        val t = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val a = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
        set(fromSession ?: fromNotification, active, state == PlaybackState.STATE_PLAYING,
            t, a, "${c?.packageName}|$t|$a")
    }

    internal fun onMediaNotification(pkg: String, icon: Bitmap?) {
        notificationPkg = pkg
        notificationArt = icon
        refresh()
    }

    private fun set(bitmap: Bitmap?, active: Boolean, run: Boolean = false, t: String = "",
                    a: String = "", k: String = "") {
        if (bitmap === art && active == playing && run == running && k == key) return
        art = bitmap
        playing = active
        running = run
        title = t
        artist = a
        key = k
        listeners.toList().forEach { it() }
    }
}

/** The prototype's way to see media sessions; grant it under notification access. */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        MediaArt.connect(this, ComponentName(this, MediaListener::class.java))
        runCatching { activeNotifications?.forEach(::onNotificationPosted) }
    }

    override fun onListenerDisconnected() = MediaArt.disconnect()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.extras.getParcelable(android.app.Notification.EXTRA_MEDIA_SESSION,
                android.media.session.MediaSession.Token::class.java) == null) return
        val icon: Icon? = n.getLargeIcon()
        val bmp = runCatching {
            icon?.loadDrawable(this)?.let { d ->
                (d as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: run {
                    val b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                    val cv = android.graphics.Canvas(b)
                    d.setBounds(0, 0, 512, 512)
                    d.draw(cv)
                    b
                }
            }
        }.getOrNull()
        MediaArt.onMediaNotification(sbn.packageName, bmp)
    }
}
