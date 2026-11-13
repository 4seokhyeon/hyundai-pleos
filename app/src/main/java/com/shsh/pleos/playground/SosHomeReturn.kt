package com.shsh.pleos.playground

import android.content.Context

/** One-shot home return after confirmed SOS handoff, including process recreation. */
internal object SosHomeReturn {
    private const val FILE = "sos-home-return"
    private const val PENDING = "navigation-completed"
    fun mark(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .edit().putBoolean(PENDING, true).apply()
    fun pending(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getBoolean(PENDING, false)
    fun clear(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .edit().remove(PENDING).apply()
}
