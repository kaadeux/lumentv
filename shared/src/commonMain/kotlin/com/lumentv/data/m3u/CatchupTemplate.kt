package com.lumentv.data.m3u

import com.lumentv.core.nowMillis
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

object CatchupTemplate {
    /** Supports the common catch-up placeholders: {utc}, {start}, {lutc}, {end}, {utcend}, {duration}, {Y}{m}{d}{H}{M}{S}. */
    fun fill(template: String, startMs: Long, stopMs: Long, nowMs: Long = nowMillis()): String {
        val utc = (startMs / 1000).toString()
        val end = (stopMs / 1000).toString()
        val dur = ((stopMs - startMs) / 1000).toString()
        val t = Instant.fromEpochMilliseconds(startMs).toLocalDateTime(TimeZone.UTC)
        fun two(n: Int) = n.toString().padStart(2, '0')
        return template
            .replace("\${start}", utc).replace("{start}", utc).replace("{utc}", utc)
            .replace("\${end}", end).replace("{end}", end).replace("{utcend}", end)
            .replace("{lutc}", (nowMs / 1000).toString()).replace("\${timestamp}", (nowMs / 1000).toString())
            .replace("{duration}", dur).replace("\${duration}", dur)
            .replace("{Y}", t.year.toString().padStart(4, '0')).replace("{m}", two(t.monthNumber)).replace("{d}", two(t.dayOfMonth))
            .replace("{H}", two(t.hour)).replace("{M}", two(t.minute)).replace("{S}", two(t.second))
    }
}
