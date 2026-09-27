package pro.aduki.hermes.net.http

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.hermes.core.errors.HermesException
import pro.aduki.hermes.core.models.AppointmentRecord
import pro.aduki.hermes.core.models.ServiceRecord
import pro.aduki.hermes.core.models.Slot

/**
 * Scheduling provides network operations for appointments, services, availability, and public booking.
 */
class Scheduling(
    private val client: OkHttpClient,
    private val endpoint: String
) {
    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String): String {
        val clean = endpoint.trimEnd('/')
        val rel = path.trimStart('/')
        return "$clean/$rel"
    }

    /**
     * Lists active appointments for the authenticated user.
     */
    fun appointments(activeOnly: Boolean = true): List<AppointmentRecord> {
        val path = if (activeOnly) "user/appointments/active" else "user/appointments"
        val request = Request.Builder()
            .url(url(path))
            .get()
            .build()

        return execute(request) { body ->
            val result = mutableListOf<AppointmentRecord>()
            val array = if (body.trimStart().startsWith("[")) {
                JSONArray(body)
            } else {
                val obj = JSONObject(body)
                obj.optJSONArray("items") ?: obj.optJSONArray("data") ?: JSONArray()
            }
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                result.add(parseAppointment(item))
            }
            result
        }
    }

    /**
     * Fetches a specific appointment by its hex identifier.
     */
    fun appointment(hex: String): AppointmentRecord {
        val request = Request.Builder()
            .url(url("user/appointments/$hex"))
            .get()
            .build()

        return execute(request) { body ->
            parseAppointment(JSONObject(body))
        }
    }

    /**
     * Creates a new appointment on the server.
     */
    fun create(
        service: String,
        start: String,
        end: String,
        timezone: String = "UTC",
        notes: String = "",
        location: String = ""
    ): AppointmentRecord {
        val payload = JSONObject().apply {
            put("service", service)
            put("start", start)
            put("end", end)
            put("timezone", timezone)
            put("notes", notes)
            if (location.isNotBlank()) {
                put("location", JSONObject().put("type", location))
            }
        }

        val request = Request.Builder()
            .url(url("user/appointments"))
            .post(payload.toString().toRequestBody(mediaJson))
            .build()

        return execute(request) { body ->
            parseAppointment(JSONObject(body))
        }
    }

    /**
     * Cancels an appointment.
     */
    fun cancel(hex: String, reason: String = ""): Boolean {
        val payload = JSONObject().apply {
            if (reason.isNotBlank()) {
                put("reason", reason)
            }
        }

        val request = Request.Builder()
            .url(url("user/appointments/$hex/cancel"))
            .patch(payload.toString().toRequestBody(mediaJson))
            .build()

        return execute(request) { true }
    }

    /**
     * Lists booking services configured by the user.
     */
    fun services(): List<ServiceRecord> {
        val request = Request.Builder()
            .url(url("user/services"))
            .get()
            .build()

        return execute(request) { body ->
            val result = mutableListOf<ServiceRecord>()
            val array = JSONArray(body)
            for (i in 0 until array.length()) {
                result.add(parseService(array.getJSONObject(i)))
            }
            result
        }
    }

    /**
     * Computes available booking slots within [start, end].
     */
    fun availability(start: String, end: String, service: String = ""): List<Slot> {
        val query = if (service.isNotBlank()) "?service=$service" else ""
        val request = Request.Builder()
            .url(url("user/availability/$start/$end$query"))
            .get()
            .build()

        return execute(request) { body ->
            val obj = JSONObject(body)
            val slotsArray = obj.optJSONArray("slots") ?: JSONArray()
            val slots = mutableListOf<Slot>()
            for (i in 0 until slotsArray.length()) {
                val s = slotsArray.getJSONObject(i)
                slots.add(
                    Slot(
                        start = s.optString("start", ""),
                        end = s.optString("end", ""),
                        available = true
                    )
                )
            }
            slots
        }
    }

    /**
     * Guest books an appointment using public service slug.
     */
    fun book(
        slug: String,
        start: String,
        end: String,
        guestName: String,
        guestEmail: String,
        answers: Map<String, String> = emptyMap()
    ): AppointmentRecord {
        val payload = JSONObject().apply {
            put("start", start)
            put("end", end)
            put("guest_name", guestName)
            put("guest_email", guestEmail)
            if (answers.isNotEmpty()) {
                put("answers", JSONObject(answers))
            }
        }

        val request = Request.Builder()
            .url(url("book/$slug"))
            .post(payload.toString().toRequestBody(mediaJson))
            .build()

        return execute(request) { body ->
            val obj = JSONObject(body)
            val appt = obj.optJSONObject("appointment") ?: obj
            parseAppointment(appt)
        }
    }

    private fun parseAppointment(obj: JSONObject): AppointmentRecord {
        return AppointmentRecord(
            hex = obj.optString("hex", ""),
            service = obj.optString("service", ""),
            host = obj.optString("host", ""),
            start = obj.optString("start", ""),
            end = obj.optString("end", ""),
            timezone = obj.optString("timezone", "UTC"),
            status = obj.optString("status", "confirmed"),
            uid = obj.optString("uid", ""),
            location = obj.opt("location")?.toString() ?: "",
            notes = obj.optString("notes", "")
        )
    }

    private fun parseService(obj: JSONObject): ServiceRecord {
        return ServiceRecord(
            hex = obj.optString("hex", ""),
            slug = obj.optString("slug", ""),
            name = obj.optString("name", ""),
            description = obj.optString("description", ""),
            duration = obj.optInt("duration", 30),
            buffer = obj.optInt("buffer", 0),
            notice = obj.optInt("notice", 60),
            horizon = obj.optInt("horizon", 30),
            increment = obj.optInt("increment", 15),
            active = obj.optBoolean("active", true)
        )
    }

    private fun <T> execute(request: Request, transform: (String) -> T): T {
        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw HermesException.Network("Network request failed for ${request.url}", e)
        }

        response.use { resp ->
            val raw = resp.body?.string() ?: ""
            if (resp.code == 401 || resp.code == 403) {
                val why = Envelope.failure(raw) ?: "HTTP ${resp.code}"
                throw HermesException.Auth("Unauthorized access ($why)")
            }
            if (!resp.isSuccessful) {
                val why = Envelope.failure(raw) ?: resp.message
                throw HermesException.Network("HTTP error ${resp.code}: $why", code = resp.code)
            }
            val body = Envelope.data(raw)
            return transform(body)
        }
    }
}

