package com.shsh.pleos.playground.platform

import com.shsh.pleos.playground.BuildConfig

/** App-owned logs are off by default, including debug. SDK/system logs are separate. */
object AppLog {
    fun i(tag: String?, message: String): Int =
        if (BuildConfig.ENABLE_APP_LOGS) android.util.Log.i(tag, message) else 0

    fun w(tag: String?, message: String, error: Throwable? = null): Int =
        if (!BuildConfig.ENABLE_APP_LOGS) 0
        else if (error == null) android.util.Log.w(tag, message)
        else android.util.Log.w(tag, message, error)

    fun e(tag: String?, message: String, error: Throwable? = null): Int =
        if (!BuildConfig.ENABLE_APP_LOGS) 0
        else if (error == null) android.util.Log.e(tag, message)
        else android.util.Log.e(tag, message, error)
}
