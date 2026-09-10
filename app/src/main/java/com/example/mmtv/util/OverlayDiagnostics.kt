package com.example.mmtv.util

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only overlay measurements. It records existing work but does not schedule or alter it. */
object OverlayDiagnostics {
    private const val TAG = "MMTVOverlayDiag"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sideRecompositions = AtomicInteger()
    private val channelRecompositions = ConcurrentHashMap<Int, AtomicInteger>()
    @Volatile private var enabled = false
    @Volatile private var lastFocusedId: Int? = null
    @Volatile private var lastFocusedRecompositions = 0

    fun initialize(context: android.content.Context) {
        enabled = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        event("process_start", "diagnostic_version=1")
    }

    private fun event(name: String, details: String = "") {
        if (enabled) Log.i(TAG, "pid=${Process.myPid()} uptime_ms=${SystemClock.elapsedRealtime()} event=$name $details")
    }

    fun sideOverlayRecomposed() {
        if (enabled) sideRecompositions.incrementAndGet()
    }

    fun reportSideOverlaySecond(isVisible: Boolean) {
        if (!enabled || !isVisible) return
        event("side_recompositions_second", "count=${sideRecompositions.getAndSet(0)}")
    }

    fun channelRecomposed(id: Int) {
        if (enabled) channelRecompositions.getOrPut(id) { AtomicInteger() }.incrementAndGet()
    }

    fun focusEvent(kind: String, id: Int) {
        if (!enabled) return
        val count = channelRecompositions[id]?.get() ?: 0
        val previous = lastFocusedId
        val previousCount = lastFocusedRecompositions
        lastFocusedId = id
        lastFocusedRecompositions = count
        val started = SystemClock.elapsedRealtime()
        event("focus_event", "kind=$kind id=$id recompositions=$count previous_id=${previous ?: -1} previous_recompositions=$previousCount")
        mainHandler.post {
            event("focus_main_idle", "kind=$kind id=$id elapsed_ms=${SystemClock.elapsedRealtime() - started}")
        }
        Choreographer.getInstance().postFrameCallback {
            event("focus_next_frame", "kind=$kind id=$id elapsed_ms=${SystemClock.elapsedRealtime() - started}")
        }
    }

    fun categorySelectedStart(index: Int): Long {
        if (!enabled) return 0L
        return SystemClock.elapsedRealtime().also { event("category_selected_start", "index=$index") }
    }

    fun categorySelectedEnd(index: Int, started: Long) {
        if (enabled) event("category_selected_end", "index=$index elapsed_ms=${SystemClock.elapsedRealtime() - started}")
    }

    fun prefetchStart(index: Int, categoryId: String?, itemCount: Int, mapWrites: Int): Long {
        if (!enabled) return 0L
        return SystemClock.elapsedRealtime().also {
            event("prefetch_start", "index=$index category=${categoryId ?: ""} items=$itemCount channel_map_writes=$mapWrites")
        }
    }

    fun prefetchEnd(index: Int, started: Long, missing: Int, putAllEntries: Int) {
        if (enabled) event("prefetch_end", "index=$index elapsed_ms=${SystemClock.elapsedRealtime() - started} missing=$missing full_epg_putAll_entries=$putAllEntries")
    }
}
