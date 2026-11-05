package com.shsh.pleos.playground.gleo

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.shsh.pleos.playground.PublicDataProbeActivity
import com.shsh.pleos.playground.ui.probe.PublicDataProbeState
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

/** Third schema function: current SOS results, selection and confirmed navigation. */
object GleoSosContract {
    const val ACTION = "RESTROOM_SOS"
    const val QUALIFIED_ACTION = "com.shsh.pleos.playground.RESTROOM_SOS"
    private const val FORWARDED = "com.shsh.pleos.playground.gleo.SOS_REQUEST"
    val operations = setOf("FIND", "READ", "SELECT", "NAVIGATE")
    val targets = setOf("RESTROOM", "PARKING")
    data class Request(val id: String, val operation: String, val index: Int?, val target: String?,
        val confirmed: Boolean, val listId: String?, val deadline: Long, val diagnostic: Boolean)
    fun matches(action: String?) = action == ACTION || action == QUALIFIED_ACTION

    @Suppress("DEPRECATION")
    fun readPublic(intent: Intent): Request {
        require(matches(intent.action))
        val merged = JSONObject()
        intent.extras?.let { extras ->
            for (key in listOf("operation", "index", "target", "confirmed", "listId")) {
                if (extras.containsKey(key)) merged.put(key, extras.get(key) ?: JSONObject.NULL)
            }
            if (extras.containsKey(GleoSearchContract.PACKED_PARAMETERS)) {
                val raw = extras.get(GleoSearchContract.PACKED_PARAMETERS) as? String ?: error("invalid_parameters")
                require(raw.length <= 4096)
                var decoded = decode(raw)
                if (decoded is String) decoded = decode(decoded)
                val packed = decoded as? JSONObject ?: error("invalid_parameters")
                for (key in listOf("operation", "index", "target", "confirmed", "listId")) {
                    if (packed.has(key)) {
                        if (merged.has(key)) require(merged.opt(key).toString() == packed.opt(key).toString())
                        merged.put(key, packed.opt(key))
                    }
                }
            }
        }
        val operation = text(merged, "operation") ?: error("operation_missing")
        require(operation in operations)
        val target = text(merged, "target")
        require(target == null || target in targets)
        val listId = text(merged, "listId")?.also { UUID.fromString(it) }
        val index = if (!merged.has("index") || merged.isNull("index")) null else {
            val raw = merged.get("index")
            require(raw is Number || raw is String)
            val number = raw.toString().toDoubleOrNull() ?: error("invalid_index")
            require(number.isFinite() && number in 1.0..100.0 && number % 1.0 == 0.0)
            number.toInt()
        }
        val confirmed = if (!merged.has("confirmed") || merged.isNull("confirmed")) false else when (val value = merged.get("confirmed")) {
            is Boolean -> value
            "true" -> true
            "false" -> false
            else -> error("invalid_confirmation")
        }
        return Request(UUID.randomUUID().toString(), operation, index, target, confirmed, listId,
            SystemClock.elapsedRealtime() + GleoSearchContract.BUDGET_MS, GleoSearchContract.diagnostic(intent))
    }
    private fun text(value: JSONObject, key: String): String? {
        if (!value.has(key) || value.isNull(key)) return null
        return (value.get(key) as? String ?: error("invalid_parameters")).also { require(it.length <= 120 && it.none { char -> char.isISOControl() }) }
    }
    private fun decode(raw: String): Any {
        val parser = JSONTokener(raw)
        val result = parser.nextValue()
        require(parser.nextClean() == '\u0000')
        return result
    }
    fun forward(context: Context, request: Request): Intent = Intent(context, PublicDataProbeActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(FORWARDED, JSONObject().put("id", request.id).put("operation", request.operation)
            .put("index", request.index).put("target", request.target).put("confirmed", request.confirmed)
            .put("listId", request.listId).put("deadline", request.deadline).put("diagnostic", request.diagnostic).toString())

    fun takeForwarded(intent: Intent): Request? {
        val raw = intent.getStringExtra(FORWARDED) ?: return null
        intent.removeExtra(FORWARDED)
        return runCatching {
            require(raw.length <= 4096)
            val value = JSONObject(raw)
            val id = value.getString("id").also { UUID.fromString(it) }
            val operation = value.getString("operation").also { require(it in operations) }
            val target = text(value, "target").also { require(it == null || it in targets) }
            Request(id, operation, if (value.isNull("index")) null else value.getInt("index"), target,
                value.getBoolean("confirmed"), text(value, "listId"), value.getLong("deadline"), value.getBoolean("diagnostic"))
        }.getOrNull()
    }
    fun reply(status: String, message: String, reason: String, data: String? = null) =
        GleoReply(status, message, data, reason, ACTION)

    /** Same order as the screen. Coordinates stay in the app, not the assistant response. */
    fun results(state: PublicDataProbeState, selectedIndex: Int? = null): GleoReply {
        val data = JSONArray().put(JSONObject().put("listId", state.candidateSetId)
            .put("resultCount", state.candidates.size).put("distanceKind", "straight_line")
            .put("selectedIndex", selectedIndex).put("liveOpeningVerified", false).put("parkingVacancyVerified", false))
        state.candidates.forEachIndexed { index, candidate ->
            data.put(JSONObject().put("index", index + 1).put("name", candidate.restroom.name.take(200))
                .put("address", candidate.restroom.address.take(300)).put("distanceMeters", candidate.distanceMeters.toInt())
                .put("registeredHours", candidate.restroom.hours ?: "unknown")
                .put("parkingName", candidate.parking?.name?.take(200) ?: "none_registered_within_300m")
                .put("parkingDistanceMeters", candidate.parkingDistanceMeters?.toInt())
                .put("parkingFee", candidate.parking?.fee ?: "unknown"))
        }
        return reply(if (state.candidates.isEmpty()) "success" else "asking",
            if (state.candidates.isEmpty()) "No restroom candidates were found in the prepared Gyeonggi data within five kilometers."
            else "The current restroom candidates are displayed. Select a candidate number. Distances are straight-line; opening, parking vacancy and walking access are unverified.",
            "sos_results", data.toString())
    }
}
