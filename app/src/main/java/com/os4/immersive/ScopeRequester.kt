package com.os4.immersive

import android.app.Application
import android.os.SystemClock
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class ImmersiveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(ScopeRequester)
    }
}

/** LSPosed owns the approval and the actual scope. The callback only tracks the prompt. */
internal object ScopeRequester : XposedServiceHelper.OnServiceListener {
    private var service: XposedService? = null
    private var request: XposedService.OnScopeEventListener? = null
    private var requestedAt = 0L
    private var failed = false

    @Synchronized override fun onServiceBind(service: XposedService) {
        if (this.service !== service) {
            this.service = service
            request = null
            failed = false
        }
    }

    @Synchronized override fun onServiceDied(service: XposedService) {
        if (this.service === service) {
            this.service = null
            request = null
            failed = false
        }
    }

    @Synchronized fun state(): String {
        val bound = service ?: return "enable-module"
        val scope = runCatching { bound.scope }.getOrNull() ?: return "enable-module"
        if (EdgeLock.SYSTEMUI in scope) {
            request = null
            failed = false
            return "ready"
        }
        if (request != null && SystemClock.elapsedRealtime() - requestedAt >= 60_000L) {
            request = null
            failed = true
        }
        return when {
            request != null -> "awaiting-approval"
            failed -> "approval-failed"
            else -> "request-scopes"
        }
    }

    fun requestScope() {
        val (bound, listener) = synchronized(this) {
            val bound = service ?: return
            if (state() !in listOf("request-scopes", "approval-failed")) return
            val listener = object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(scope: List<String>) = finish(this, false)
                override fun onScopeRequestFailed(message: String) = finish(this, true)
            }
            request = listener
            requestedAt = SystemClock.elapsedRealtime()
            failed = false
            bound to listener
        }
        runCatching { bound.requestScope(listOf(EdgeLock.SYSTEMUI), listener) }
            .onFailure { finish(listener, true) }
    }

    @Synchronized private fun finish(listener: XposedService.OnScopeEventListener, failed: Boolean) {
        if (request === listener) {
            request = null
            this.failed = failed
        }
    }
}
