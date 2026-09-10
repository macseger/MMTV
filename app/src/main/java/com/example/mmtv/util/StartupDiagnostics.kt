package com.example.mmtv.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.example.mmtv.database.MediaDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Debug-only, logcat-only diagnostics. Never logs credentials, URLs or media names. */
object StartupDiagnostics {
    @Volatile var enabled = false
        private set
    private val sequence = AtomicLong()
    private val foreground = AtomicInteger()
    private val workers = AtomicInteger()

    class Trigger(val value: String) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Trigger>
    }

    data class Span(val id: Long, val phase: String, val trigger: String, val started: Long)

    fun initialize(context: Context) {
        enabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        event("process_start", "diagnostic_version=1")
    }

    fun event(name: String, details: String = "") {
        if (enabled) Log.i("MMTVDiag", "pid=${Process.myPid()} uptime_ms=${SystemClock.elapsedRealtime()} event=$name $details")
    }

    suspend fun start(phase: String, details: String = ""): Span {
        val span = Span(sequence.incrementAndGet(), phase,
            currentCoroutineContext()[Trigger]?.value ?: "unspecified", SystemClock.elapsedRealtime())
        if (!enabled) return span
        if (phase == "foreground_startup") foreground.incrementAndGet()
        if (phase == "worker") workers.incrementAndGet()
        event("start", "span=${span.id} phase=$phase trigger=${span.trigger} foreground=${foreground.get()} workers=${workers.get()} overlap=${foreground.get() > 0 && workers.get() > 0} $details")
        return span
    }

    fun end(span: Span, outcome: String) {
        if (!enabled) return
        event("end", "span=${span.id} phase=${span.phase} trigger=${span.trigger} elapsed_ms=${SystemClock.elapsedRealtime() - span.started} outcome=$outcome foreground=${foreground.get()} workers=${workers.get()} overlap=${foreground.get() > 0 && workers.get() > 0}")
        if (span.phase == "foreground_startup") foreground.decrementAndGet()
        if (span.phase == "worker") workers.decrementAndGet()
    }

    suspend inline fun <T> timed(phase: String, details: String = "", block: suspend () -> T): T {
        if (!enabled) return block()
        val span = start(phase, details)
        var outcome = "returned"
        try {
            return block()
        } catch (e: Throwable) {
            outcome = if (e is CancellationException) "cancelled" else "threw_${e.javaClass.simpleName}"
            throw e
        } finally {
            end(span, outcome)
        }
    }

    suspend fun rows(dao: MediaDao, checkpoint: String) {
        if (!enabled) return
        timed("diagnostic_row_counts", "checkpoint=$checkpoint") {
            try {
                val counts = withContext(Dispatchers.IO) { dao.getDiagnosticCounts() }
                event("rows", "checkpoint=$checkpoint trigger=${currentCoroutineContext()[Trigger]?.value ?: "unspecified"} media_items=${counts.mediaItems} epg_listings=${counts.epgListings} channel_metadata=${counts.channelMetadata} picons=${counts.picons}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                event("rows_unavailable", "checkpoint=$checkpoint error=${e.javaClass.simpleName}")
            }
        }
    }
}
