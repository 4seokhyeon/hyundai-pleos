package com.shsh.pleos.playground.data

import kotlin.math.roundToInt

/** Demo-only display rule; does not measure pitch/rhythm accuracy or singing ability. */
object FunScorePolicy {
    const val VERSION = "fun_v1"

    fun convert(rawMos: Double): Int {
        require(rawMos.isFinite()) { "A finite successful model result is required" }
        return (60.0 + rawMos * 8.0).coerceIn(60.0, 100.0).roundToInt()
    }
}
