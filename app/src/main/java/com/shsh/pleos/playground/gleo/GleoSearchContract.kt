package com.shsh.pleos.playground.gleo

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.shsh.pleos.playground.BuildConfig
import com.shsh.pleos.playground.VideoProbeActivity
import java.util.UUID
import org.json.JSONObject
import org.json.JSONTokener

/** Public Gleo input and private app forwarding are intentionally separate contracts. */
object GleoSearchContract {
    const val ACTION = "SEARCH_SONG"
    const val QUALIFIED_ACTION = "com.shsh.pleos.playground.SEARCH_SONG"
    const val SELECT_ACTION = "SELECT_SONG"
    const val QUALIFIED_SELECT_ACTION = "com.shsh.pleos.playground.SELECT_SONG"
    const val CONTROL_ACTION = "CONTROL_KARAOKE"
    const val QUALIFIED_CONTROL_ACTION = "com.shsh.pleos.playground.CONTROL_KARAOKE"
    private const val OPERATION = "operation"
    private const val SELECT = "SELECT"
    private const val OPEN_HOME = "OPEN_HOME"

    fun functionName(action: String?): String? = when (action) {
        ACTION, QUALIFIED_ACTION -> ACTION
        SELECT_ACTION, QUALIFIED_SELECT_ACTION -> SELECT_ACTION
        CONTROL_ACTION, QUALIFIED_CONTROL_ACTION -> CONTROL_ACTION
        else -> null
    }
    fun matchesAction(action: String?): Boolean = functionName(action) != null
    const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    const val PARAMETER = "query"
    const val PACKED_PARAMETERS = "ACTION_PARAM"
    const val DIAGNOSTIC = "com.shsh.pleos.playground.gleo.DIAGNOSTIC"
    private const val FORWARDED = "com.shsh.pleos.playground.gleo.FORWARDED"
    private const val REQUEST_ID = "com.shsh.pleos.playground.gleo.REQUEST_ID"
    private const val FUNCTION = "com.shsh.pleos.playground.gleo.FUNCTION"
    private const val INDEX = "com.shsh.pleos.playground.gleo.INDEX"
    private const val DEADLINE = "com.shsh.pleos.playground.gleo.DEADLINE"
    const val MAX_QUERY = 120
    const val BUDGET_MS = 6_000L // Gleo's documented action timeout is 7 seconds.
    private const val MAX_PACKED = 4_096

    data class Request(val id: String, val query: String?, val deadline: Long, val diagnostic: Boolean, val function: String = ACTION, val index: Int? = null, val opensHome: Boolean = false) {
        val selectsSong: Boolean get() = !opensHome && (function == SELECT_ACTION || function == CONTROL_ACTION)
    }
    sealed interface Input {
        data class Accepted(val request: Request) : Input
        data object Invalid : Input
    }

    fun diagnostic(intent: Intent): Boolean = BuildConfig.DEBUG &&
        runCatching { intent.getBooleanExtra(DIAGNOSTIC, false) }.getOrDefault(false)

    /** The guide describes both individual extras and a JSON ACTION_PARAM example. */
    @Suppress("DEPRECATION")
    fun readPublic(intent: Intent): Input = try {
        val function = functionName(intent.action)
        if (function == SELECT_ACTION || function == CONTROL_ACTION) readControl(intent, function)
        else if (!matchesAction(intent.action)) Input.Invalid
        else {
            val extras = intent.extras
            var supplied = false
            var value: String? = null
            if (extras?.containsKey(PARAMETER) == true) {
                val raw = extras.get(PARAMETER)
                if (raw != null && raw !is String) throw IllegalArgumentException()
                supplied = true
                value = normalize(raw as String?)
            }
            if (extras?.containsKey(PACKED_PARAMETERS) == true) {
                val raw = extras.get(PACKED_PARAMETERS) as? String ?: throw IllegalArgumentException()
                if (raw.length > MAX_PACKED) throw IllegalArgumentException()
                var decoded = decode(raw)
                // Accept a valid JSON object string wrapped in one extra JSON string layer.
                if (decoded is String) decoded = decode(decoded)
                val objectValue = decoded as? JSONObject ?: throw IllegalArgumentException()
                if (objectValue.has(PARAMETER)) {
                    val packed = objectValue.opt(PARAMETER)
                    if (packed != JSONObject.NULL && packed !is String) throw IllegalArgumentException()
                    val next = normalize(packed as? String)
                    if (supplied && value != next) throw IllegalArgumentException()
                    value = next
                }
            }
            Input.Accepted(Request(UUID.randomUUID().toString(), value,
                SystemClock.elapsedRealtime() + BUDGET_MS, diagnostic(intent)))
        }
    } catch (_: Exception) { Input.Invalid }

    @Suppress("DEPRECATION")
    private fun readControl(intent: Intent, function: String): Input {
        val extras = intent.extras
        var index: Int? = null
        val hasOperation = extras?.containsKey(OPERATION) == true
        var operation = if (hasOperation) parseOperation(extras?.get(OPERATION)) else null
        var supplied = false
        if (extras?.containsKey("index") == true) {
            index = parseIndex(extras.get("index")); supplied = true
        }
        if (extras?.containsKey(PACKED_PARAMETERS) == true) {
            val raw = extras.get(PACKED_PARAMETERS) as? String ?: throw IllegalArgumentException()
            if (raw.length > MAX_PACKED) throw IllegalArgumentException()
            var decoded = decode(raw)
            if (decoded is String) decoded = decode(decoded)
            val packed = decoded as? JSONObject ?: throw IllegalArgumentException()
            if (packed.has(OPERATION)) {
                val next = parseOperation(packed.opt(OPERATION))
                require(!hasOperation || operation == next)
                operation = next
            }
            if (packed.has("index")) {
                val next = parseIndex(packed.opt("index"))
                if (supplied && index != next) throw IllegalArgumentException()
                index = next
            }
        }
        // A cached legacy SELECT_SONG schema may still send an index without an operation.
        val opensHome = function == CONTROL_ACTION && operation == OPEN_HOME
        require(if (function == CONTROL_ACTION) operation != null else operation == null || operation == SELECT)
        require(!opensHome || index == null)
        return Input.Accepted(Request(UUID.randomUUID().toString(), null,
            SystemClock.elapsedRealtime() + BUDGET_MS, diagnostic(intent), function, index, opensHome))
    }

    private fun parseOperation(value: Any?): String {
        val operation = value as? String ?: throw IllegalArgumentException()
        require(operation == SELECT || operation == OPEN_HOME)
        return operation
    }

    private fun parseIndex(value: Any?): Int? {
        if (value == null || value == JSONObject.NULL) return null
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: throw IllegalArgumentException()
            else -> throw IllegalArgumentException()
        }
        if (!number.isFinite() || number < 1 || number > 100 || number % 1.0 != 0.0) throw IllegalArgumentException()
        return number.toInt()
    }

    fun forward(context: Context, request: Request): Intent = Intent(context, VideoProbeActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(FORWARDED, true).putExtra(REQUEST_ID, request.id)
        .putExtra(PARAMETER, request.query).putExtra(DEADLINE, request.deadline)
        .putExtra(DIAGNOSTIC, request.diagnostic)
        .putExtra(FUNCTION, request.function).apply { request.index?.let { putExtra(INDEX, it) } }

    /** Only the non-exported workspace receives these app-created extras. */
    fun takeForwarded(intent: Intent): Request? {
        if (!intent.getBooleanExtra(FORWARDED, false)) return null
        return try {
            val id = intent.getStringExtra(REQUEST_ID) ?: return null
            UUID.fromString(id)
            val function = intent.getStringExtra(FUNCTION) ?: ACTION
            require(function == ACTION || function == SELECT_ACTION || function == CONTROL_ACTION)
            val index = if (intent.hasExtra(INDEX)) parseIndex(intent.getIntExtra(INDEX, 0)) else null
            Request(id, normalize(intent.getStringExtra(PARAMETER)), intent.getLongExtra(DEADLINE, 0), diagnostic(intent), function, index)
        } catch (_: Exception) { null }
        finally {
            listOf(FORWARDED, REQUEST_ID, PARAMETER, DEADLINE, DIAGNOSTIC, FUNCTION, INDEX).forEach(intent::removeExtra)
        }
    }

    private fun normalize(value: String?): String? {
        if (value == null) return null
        if (value.length > MAX_QUERY || value.any { it.isISOControl() }) throw IllegalArgumentException()
        return value.trim().takeIf { it.isNotEmpty() }
    }

    private fun decode(value: String): Any {
        val parser = JSONTokener(value)
        val result = parser.nextValue()
        if (parser.nextClean() != '\u0000') throw IllegalArgumentException()
        return result
    }
}
