package dev.balaji.rem.core

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

val TORONTO: ZoneId = ZoneId.of("America/Toronto")

/** Toronto wall clock time, for example "2026-01-14T23:00". */
fun toronto(local: String): Instant = LocalDateTime.parse(local).atZone(TORONTO).toInstant()

fun clockAt(local: String): Clock = Clock.fixed(toronto(local), TORONTO)
