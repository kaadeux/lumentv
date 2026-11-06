package com.lumentv.core

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

fun dateOf(t: Long): LocalDate = Instant.fromEpochMilliseconds(t).toLocalDateTime(TimeZone.currentSystemDefault()).date

private val weekDays = listOf("Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche")
private val months = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")

/** "Aujourd'hui", "Demain", "Hier", "Samedi 8 nov.". */
fun dayLabel(t: Long, now: Long = nowMillis()): String {
    val d = dateOf(t)
    return when (d.toEpochDays() - dateOf(now).toEpochDays()) {
        0 -> "Aujourd'hui"
        1 -> "Demain"
        -1 -> "Hier"
        else -> weekDays[d.dayOfWeek.ordinal] + " " + d.dayOfMonth + " " + months[d.monthNumber - 1]
    }
}
