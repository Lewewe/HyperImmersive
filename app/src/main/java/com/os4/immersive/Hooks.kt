package com.os4.immersive

import android.app.Application
import android.content.Context
import android.service.notification.StatusBarNotification
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * HyperImmersive's own LSPosed side, scoped to SystemUI - which draws and answers the edge
 * gestures - so HyperMusicCover-Enhanced never has to reach into it for this. All it does there
 * is listen for [EdgeLock]'s broadcasts once the process has a context.
 *
 * MiuiHome's back bulge stays: on OS4 the launcher is Flutter and native code, nothing a Java
 * module can hook.
 */
class Hooks : XposedModule() {
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != EdgeLock.SYSTEMUI || !param.isFirstPackage) return
        // attach, not onCreate: every Application goes through it, whether or not its own
        // onCreate calls super.
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        hook(attach).intercept { chain ->
            val result = chain.proceed()
            val app = chain.thisObject as Application
            runCatching { EdgeLock.listen(app) }
            runCatching { hideWatcher(app.classLoader) }
                .onFailure { log(android.util.Log.WARN, StandbyService.TAG, "watcher notification stays: $it") }
            result
        }
    }

    /**
     * A foreground service must post a notification and the system lifts its blocked channel back to
     * low, so the watcher's is dropped here, in the listener feeding SystemUI's collection. Its
     * removal has to go too: the collection throws on a key it does not know.
     */
    private fun hideWatcher(loader: ClassLoader) {
        val listener = loader.loadClass("com.android.systemui.statusbar.notification.collection.NotifCollection\$1")
        listener.declaredMethods
            .filter { it.name == "onNotificationPosted" || it.name == "onNotificationRemoved" }
            .forEach { method ->
                hook(method).intercept { chain ->
                    val sbn = chain.args.firstOrNull() as? StatusBarNotification
                    if (sbn?.packageName == EdgeLock.APP && sbn.id == StandbyService.WATCHER_ID) null else chain.proceed()
                }
            }
    }
}
