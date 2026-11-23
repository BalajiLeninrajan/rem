package dev.balaji.rem.ring.ui

import kotlinx.serialization.Serializable

/** The ringing screen. The start destination of the ringing activity. */
@Serializable
data object RingingRoute

@Serializable
data object RingScannerRoute

@Serializable
data object TypedFallbackRoute
