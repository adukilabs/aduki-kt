package pro.aduki.net.http

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.Address
import pro.aduki.core.models.AppointmentRecord
import pro.aduki.core.models.Availability
import pro.aduki.core.models.BookRequest
import pro.aduki.core.models.BookingRecord
import pro.aduki.core.models.CalendarRecord
import pro.aduki.core.models.OfferRecord
import pro.aduki.core.models.OfferRequest
import pro.aduki.core.models.Place
import pro.aduki.core.models.ServiceRequest
import pro.aduki.core.models.ServiceRecord
import pro.aduki.core.models.Slot
import pro.aduki.core.models.Stats
import pro.aduki.core.models.Window
import java.time.Instant

/**
 * Scheduling is the REST client for calendar events, booking pages
 * ("services"), their schedules and bookings (ADK-CAL-002):
 *
 * - events: `GET /user/events`, `GET /user/events/range/{start}/{end}`
 *   (pages of `{items, total, page, pages}`), `POST /user/events`
 *   `{ical, calendar?, notify?}`, `PATCH /user/events/{hex}` `{ical, notify?}`,
 *   `DELETE /user/events/{hex}?notify=`, `POST /user/events/{hex}/reply`
 *   `{partstat, comment?}`. An event is an iCalendar object; the server
 *   derives every other field from it. With `notify`, an event the account
 *   organizes goes to its attendees as an iTIP REQUEST (CANCEL on delete).
 * - services: `GET|POST /user/pages`, `GET|PATCH|DELETE /user/pages/{hex}`,
 *   `GET /user/pages/{hex}/stats?from&to`.
 * - availability: `GET|POST /user/schedules`, `PUT /user/schedules/{hex}/rules`.
 * - offers a page sells: `GET /user/schedule/services?page=`.
 * - slots and bookings: `GET /user/book/pages/{hex}/slots?from&to&length`,
 *   `POST /user/book/pages/{hex}/bookings` (signed in),
 *   `POST /book/pages/{hex}/bookings` (public, guest), `GET /user/bookings`,
 *   `GET|PATCH /user/bookings/{hex}`.
 */
class Scheduling(
    private val client: OkHttpClient,
    private val endpoint: String
) {
    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String): HttpUrl {
        val clean = endpoint.trimEnd('/')
        return "$clean/${path.trimStart('/')}".toHttpUrl()
    }

    // ── Events ───────────────────────────────────────────────────────

    /** Every event of the account (`GET /user/events`, all pages). */
    fun events(): List<AppointmentRecord> = paged(url("user/events"), ::parseEvent)

    /** Events overlapping `[start, end)` (`GET /user/events/range/{start}/{end}`), repeating events with a later occurrence in it included. */
    fun range(start: Instant, end: Instant): List<AppointmentRecord> {
        val path = url("user/events/range").newBuilder()
            .addPathSegment(start.toString())
            .addPathSegment(end.toString())
            .build()
        return paged(path, ::parseEvent)
    }

    /**
     * Creates an event from an iCalendar object, in [calendar] (a hex) or the
     * default calendar. With [notify], an event the account organizes is
     * sent to its attendees (iTIP REQUEST).
     */
    fun createEvent(ical: String, calendar: String = "", notify: Boolean = false): AppointmentRecord {
        val payload = JSONObject().put("ical", ical)
        if (calendar.isNotBlank()) payload.put("calendar", calendar)
        if (notify) payload.put("notify", true)
        val request = Request.Builder().url(url("user/events")).post(json(payload)).build()
        return execute(request) { parseEvent(JSONObject(it)) }
    }

    /**
     * Replaces an event's iCalendar object (the server bumps the sequence).
     * With [notify], an event the account organizes is sent to its
     * attendees again (iTIP REQUEST).
     */
    fun replaceEvent(hex: String, ical: String, notify: Boolean = false): AppointmentRecord {
        val payload = JSONObject().put("ical", ical)
        if (notify) payload.put("notify", true)
        val request = Request.Builder()
            .url(url("user/events").newBuilder().addPathSegment(hex).build())
            .patch(json(payload))
            .build()
        return execute(request) { parseEvent(JSONObject(it)) }
    }

    /**
     * Answers an invitation: `POST /user/events/{hex}/reply`
     * `{partstat, comment?}` (`ACCEPTED`, `DECLINED`, `TENTATIVE`). The
     * server sets the `PARTSTAT` of the account's attendee, sends the
     * organizer an iTIP REPLY carrying [comment], and answers with the event.
     * An event without an organizer, or without the account among its
     * attendees, is refused (422).
     */
    fun reply(hex: String, partstat: String, comment: String? = null): AppointmentRecord {
        val payload = JSONObject().put("partstat", partstat)
        if (!comment.isNullOrBlank()) payload.put("comment", comment)
        val request = Request.Builder()
            .url(url("user/events").newBuilder().addPathSegment(hex).addPathSegment("reply").build())
            .post(json(payload))
            .build()
        return execute(request) { parseEvent(JSONObject(it)) }
    }

    /** Deletes an event; with [notify], the attendees of an event the account organizes get an iTIP CANCEL. */
    fun removeEvent(hex: String, notify: Boolean = false) {
        val path = url("user/events").newBuilder().addPathSegment(hex)
        if (notify) path.addQueryParameter("notify", "true")
        val request = Request.Builder()
            .url(path.build())
            .delete()
            .build()
        execute(request) { }
    }

    // ── Calendars ────────────────────────────────────────────────────

    /** The account's calendar collections (`GET /user/calendars`). */
    fun calendars(): List<CalendarRecord> = paged(url("user/calendars"), ::parseCalendar)

    /**
     * Creates a new calendar collection (`POST /user/calendars`).
     */
    fun createCalendar(
        name: String,
        color: String? = null,
        timezone: String? = null,
        description: String? = null
    ): CalendarRecord {
        val payload = JSONObject().put("name", name.trim())
        color?.let { payload.put("color", it.trim()) }
        timezone?.let { payload.put("timezone", it.trim()) }
        description?.let { payload.put("description", it.trim()) }
        val request = Request.Builder().url(url("user/calendars")).post(json(payload)).build()
        return execute(request) { parseCalendar(JSONObject(it)) }
    }

    /**
     * Edits a calendar (`PATCH /user/calendars/{hex}`).
     */
    fun updateCalendar(
        hex: String,
        name: String? = null,
        color: String? = null,
        timezone: String? = null,
        description: String? = null,
        busy: Boolean? = null
    ): CalendarRecord {
        val payload = JSONObject()
        name?.let { payload.put("name", it.trim()) }
        color?.let { payload.put("color", it.trim()) }
        timezone?.let { payload.put("timezone", it.trim()) }
        description?.let { payload.put("description", it.trim()) }
        busy?.let { payload.put("busy", it) }
        val request = Request.Builder()
            .url(url("user/calendars").newBuilder().addPathSegment(hex).build())
            .patch(json(payload))
            .build()
        return execute(request) { parseCalendar(JSONObject(it)) }
    }

    /** Renames a calendar (`PATCH /user/calendars/{hex}`). */
    fun renameCalendar(hex: String, name: String): CalendarRecord = updateCalendar(hex, name = name)

    /** Deletes a calendar and its events (`DELETE /user/calendars/{hex}`). */
    fun removeCalendar(hex: String) {
        val request = Request.Builder()
            .url(url("user/calendars").newBuilder().addPathSegment(hex).build())
            .delete()
            .build()
        execute(request) { }
    }

    // ── Services (booking pages) ─────────────────────────────────────

    /** The account's booking pages (`GET /user/pages`). */
    fun services(): List<ServiceRecord> {
        val request = Request.Builder().url(url("user/pages")).get().build()
        return execute(request) { body -> objects(JSONArray(body)).map(::parseService) }
    }

    /** One booking page (`GET /user/pages/{hex}`). */
    fun service(hex: String): ServiceRecord {
        val request = Request.Builder().url(url("user/pages").newBuilder().addPathSegment(hex).build()).get().build()
        return execute(request) { parseService(JSONObject(it)) }
    }

    /** Creates a booking page on [schedule] (a hex; the main schedule when blank). */
    fun createService(service: ServiceRequest, schedule: String = ""): ServiceRecord {
        val request = Request.Builder().url(url("user/pages")).post(json(page(service, schedule))).build()
        return execute(request) { parseService(JSONObject(it)) }
    }

    /** Edits a booking page; null fields of [service] keep their value. A non-blank [schedule] moves it to that schedule. */
    fun editService(hex: String, service: ServiceRequest, schedule: String = ""): ServiceRecord {
        val request = Request.Builder()
            .url(url("user/pages").newBuilder().addPathSegment(hex).build())
            .patch(json(page(service, schedule)))
            .build()
        return execute(request) { parseService(JSONObject(it)) }
    }

    /** Deletes a booking page; the server pauses one that has bookings instead (`409`). */
    fun removeService(hex: String) {
        val request = Request.Builder().url(url("user/pages").newBuilder().addPathSegment(hex).build()).delete().build()
        execute(request) { }
    }

    // ── Schedules (availability) ─────────────────────────────────────

    /** The account's schedules as `(hex, name)` (`GET /user/schedules`). */
    fun schedules(): List<Pair<String, String>> {
        val request = Request.Builder().url(url("user/schedules")).get().build()
        return execute(request) { body ->
            objects(JSONArray(body)).map { it.optString("hex") to it.optString("name") }
        }
    }

    /** Creates a schedule open in [windows], read in [zone]; returns its hex. */
    fun createSchedule(name: String, zone: String, windows: List<Window>): String {
        val payload = JSONObject().put("name", name).put("zone", zone).put("rules", rules(windows))
        val request = Request.Builder().url(url("user/schedules")).post(json(payload)).build()
        return execute(request) { JSONObject(it).getString("hex") }
    }

    /** Replaces the weekly windows of [schedule] (`PUT /user/schedules/{hex}/rules`). */
    fun rules(schedule: String, windows: List<Window>) {
        val request = Request.Builder()
            .url(url("user/schedules").newBuilder().addPathSegment(schedule).addPathSegment("rules").build())
            .put(rules(windows).toString().toRequestBody(mediaJson))
            .build()
        execute(request) { }
    }

    /** Reads the schedule and its weekly rules (`GET /user/schedules/{hex}`). */
    fun schedule(hex: String): Availability {
        val request = Request.Builder()
            .url(url("user/schedules").newBuilder().addPathSegment(hex).build())
            .get()
            .build()
        return execute(request) { body ->
            val obj = JSONObject(body)
            val zone = obj.optString("zone", "UTC")
            val rules = obj.optJSONArray("rules") ?: JSONArray()
            val windows = (0 until rules.length()).mapNotNull { i ->
                val r = rules.optJSONObject(i) ?: return@mapNotNull null
                val days = strings(r.optJSONArray("days"))
                val begins = r.optString("begins")
                val finishes = r.optString("finishes")
                Window(days, begins, finishes)
            }
            Availability(zone, windows)
        }
    }

    // ── Slots and bookings ───────────────────────────────────────────

    /**
     * Open slots of a booking page in `[from, to)` (at most 62 days), for
     * [length] minutes or the page's default
     * (`GET /user/book/pages/{hex}/slots`).
     */
    fun slots(service: String, from: Instant, to: Instant, length: Int? = null): List<Slot> {
        val path = url("user/book/pages").newBuilder()
            .addPathSegment(service)
            .addPathSegment("slots")
            .addQueryParameter("from", from.toString())
            .addQueryParameter("to", to.toString())
            .apply { if (length != null) addQueryParameter("length", length.toString()) }
            .build()
        val request = Request.Builder().url(path).get().build()
        return execute(request) { body ->
            objects(JSONArray(body)).map { Slot(start = it.optString("start"), end = it.optString("end")) }
        }
    }

    /**
     * Books a slot as a guest (`POST /book/pages/{hex}/bookings`, public).
     * The returned booking carries the guest's manage token, shown once.
     * A taken slot answers `409` (`slot.taken`).
     */
    fun book(service: String, booking: BookRequest): BookingRecord {
        val payload = JSONObject()
            .put("start", booking.start.toString())
            .put("zone", booking.zone)
            .put("name", booking.name)
            .put("email", booking.email)
            .put("answers", JSONObject(booking.answers))
        booking.length?.let { payload.put("length", it) }
        if (booking.phone.isNotBlank()) payload.put("phone", booking.phone)
        if (booking.notes.isNotBlank()) payload.put("notes", booking.notes)
        if (booking.offer.isNotBlank()) payload.put("service", booking.offer)
        val request = Request.Builder()
            .url(url("book/pages").newBuilder().addPathSegment(service).addPathSegment("bookings").build())
            .post(json(payload))
            .build()
        return execute(request) { body ->
            val obj = JSONObject(body)
            parseBooking(obj.optJSONObject("booking") ?: obj).copy(token = obj.optString("token"))
        }
    }

    /**
     * Booking counts of one page for bookings starting in `[from, to)`:
     * `GET /user/pages/{hex}/stats?from&to` answers
     * `{views, bookings, confirmed, pending, cancelled, done, noshow}`;
     * `views` counts the page's public views on the days the range
     * touches. The range is at most a year.
     */
    fun stats(service: String, from: Instant, to: Instant): Stats {
        val path = url("user/pages").newBuilder()
            .addPathSegment(service)
            .addPathSegment("stats")
            .addQueryParameter("from", from.toString())
            .addQueryParameter("to", to.toString())
            .build()
        val request = Request.Builder().url(path).get().build()
        return execute(request) { body ->
            val obj = JSONObject(body)
            Stats(
                service = service,
                views = if (obj.isNull("views") || !obj.has("views")) null else obj.optInt("views"),
                bookings = obj.getInt("bookings"),
                confirmed = obj.optInt("confirmed"),
                pending = obj.optInt("pending"),
                cancelled = obj.optInt("cancelled"),
                done = obj.optInt("done"),
                noshow = obj.optInt("noshow")
            )
        }
    }

    /**
     * Books a slot as the signed-in account (`POST /user/book/pages/{hex}/bookings`
     * `{start, length?, zone, notes?, answers, service?}`): no e-mail
     * confirmation, and `internal` pages are open too.
     */
    fun reserve(service: String, booking: BookRequest): BookingRecord {
        val payload = JSONObject()
            .put("start", booking.start.toString())
            .put("zone", booking.zone)
            .put("answers", JSONObject(booking.answers))
        booking.length?.let { payload.put("length", it) }
        if (booking.notes.isNotBlank()) payload.put("notes", booking.notes)
        if (booking.offer.isNotBlank()) payload.put("service", booking.offer)
        val request = Request.Builder()
            .url(url("user/book/pages").newBuilder().addPathSegment(service).addPathSegment("bookings").build())
            .post(json(payload))
            .build()
        return execute(request) { parseBooking(JSONObject(it)) }
    }

    /** One booking with its guests (`GET /user/bookings/{hex}`). */
    fun booking(hex: String): BookingRecord {
        val request = Request.Builder().url(url("user/bookings").newBuilder().addPathSegment(hex).build()).get().build()
        return execute(request) { parseBooking(JSONObject(it)) }
    }

    /**
     * Sets a booking's state (`PATCH /user/bookings/{hex}` `{state}`):
     * `confirmed`, `cancelled`, `done` or `noshow`. The server takes no reason.
     */
    fun updateBooking(hex: String, state: String): BookingRecord {
        val request = Request.Builder()
            .url(url("user/bookings").newBuilder().addPathSegment(hex).build())
            .patch(json(JSONObject().put("state", state)))
            .build()
        return execute(request) { parseBooking(JSONObject(it)) }
    }

    /** The offers of the account's pages, of one page when [service] is set (`GET /user/schedule/services?page=`). */
    fun offers(service: String = ""): List<OfferRecord> {
        val path = url("user/schedule/services").newBuilder()
            .apply { if (service.isNotBlank()) addQueryParameter("page", service) }
            .build()
        val request = Request.Builder().url(path).get().build()
        return execute(request) { body -> objects(JSONArray(body)).map(::parseOffer) }
    }

    /**
     * Creates a priced sub-service (offer) for [page] (`POST /user/schedule/services`).
     */
    fun createOffer(page: String, request: OfferRequest): OfferRecord {
        val payload = JSONObject().put("page", page.trim())
        request.name?.let { payload.put("name", it.trim()) }
        request.slug?.let { payload.put("slug", it.trim()) }
        request.description?.let { payload.put("about", it.trim()) }
        request.length?.let { payload.put("length", it) }
        request.before?.let { payload.put("before", it) }
        request.after?.let { payload.put("after", it) }
        request.capacity?.let { payload.put("capacity", it) }
        request.price?.let { payload.put("price", it) }
        request.currency?.let { payload.put("currency", it.trim().uppercase()) }
        request.paused?.let { payload.put("paused", it) }
        val req = Request.Builder().url(url("user/schedule/services")).post(json(payload)).build()
        return execute(req) { parseOffer(JSONObject(it)) }
    }

    /**
     * Edits an offer (`PATCH /user/schedule/services/{hex}`).
     */
    fun updateOffer(hex: String, request: OfferRequest): OfferRecord {
        val payload = JSONObject()
        request.name?.let { payload.put("name", it.trim()) }
        request.slug?.let { payload.put("slug", it.trim()) }
        request.description?.let { payload.put("about", it.trim()) }
        request.length?.let { payload.put("length", it) }
        request.before?.let { payload.put("before", it) }
        request.after?.let { payload.put("after", it) }
        request.capacity?.let { payload.put("capacity", it) }
        request.price?.let { payload.put("price", it) }
        request.currency?.let { payload.put("currency", it.trim().uppercase()) }
        request.paused?.let { payload.put("paused", it) }
        val req = Request.Builder()
            .url(url("user/schedule/services").newBuilder().addPathSegment(hex).build())
            .patch(json(payload))
            .build()
        return execute(req) { parseOffer(JSONObject(it)) }
    }

    /**
     * Deletes an offer (`DELETE /user/schedule/services/{hex}`).
     * 409 Conflict once it has bookings.
     */
    fun removeOffer(hex: String) {
        val req = Request.Builder()
            .url(url("user/schedule/services").newBuilder().addPathSegment(hex).build())
            .delete()
            .build()
        execute(req) { }
    }

    /** The bookings of the account's pages (`GET /user/bookings`, all pages). */
    fun bookings(upcoming: Boolean = false): List<BookingRecord> {
        val path = url("user/bookings").newBuilder()
            .apply { if (upcoming) addQueryParameter("upcoming", "true") }
            .build()
        return paged(path, ::parseBooking)
    }

    // ── Wire shapes ──────────────────────────────────────────────────

    private fun json(obj: JSONObject) = obj.toString().toRequestBody(mediaJson)

    private fun rules(windows: List<Window>): JSONArray = JSONArray().apply {
        for (w in windows) {
            put(
                JSONObject()
                    .put("days", JSONArray(w.days.map { it.lowercase() }))
                    .put("begins", w.begins)
                    .put("finishes", w.finishes)
            )
        }
    }

    private fun page(s: ServiceRequest, schedule: String): JSONObject = JSONObject().apply {
        if (schedule.isNotBlank()) put("schedule", schedule)
        s.slug?.let { put("slug", it) }
        s.name?.let { put("name", it) }
        s.description?.let { put("about", it.ifBlank { JSONObject.NULL }) }
        s.lengths?.let { put("lengths", JSONArray(it)) }
        s.before?.let { put("before", it) }
        s.after?.let { put("after", it) }
        s.notice?.let { put("notice", it) }
        s.horizon?.let { put("horizon", it) }
        s.increment?.let { put("step", it) }
        s.visibility?.let { put("visibility", it) }
        s.confirm?.let { put("confirm", it) }
        s.place?.let { put("place", JSONObject().put("kind", it.kind).put("value", it.value)) }
        s.questions?.let { put("questions", JSONArray(it)) }
        s.reminders?.let { put("remind", JSONArray(it)) }
        s.paused?.let { put("paused", it) }
    }

    // Reads `{items, total}` pages until `total` items came.
    private fun <T> paged(base: HttpUrl, parse: (JSONObject) -> T): List<T> {
        val out = mutableListOf<T>()
        var page = 1
        while (true) {
            val path = base.newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("limit", LIMIT.toString())
                .build()
            val request = Request.Builder().url(path).get().build()
            val (items, total) = execute(request) { body ->
                val obj = JSONObject(body)
                objects(obj.optJSONArray("items") ?: JSONArray()) to obj.optInt("total", 0)
            }
            items.mapTo(out, parse)
            if (items.isEmpty() || out.size >= total) return out
            page++
        }
    }

    private fun objects(array: JSONArray): List<JSONObject> =
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    private fun text(obj: JSONObject, key: String): String =
        if (obj.isNull(key)) "" else obj.optString(key, "")

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }

    private fun parseEvent(obj: JSONObject): AppointmentRecord {
        val hex = text(obj, "hex")
        val ical = text(obj, "ical")
        if (hex.isBlank()) throw AdukiException.Network("Event without a hex in the reply")
        return AppointmentRecord(
            hex = hex,
            uid = text(obj, "uid"),
            title = text(obj, "summary"),
            start = text(obj, "start"),
            end = text(obj, "end"),
            timezone = text(obj, "timezone").ifBlank { "UTC" },
            status = text(obj, "status"),
            location = text(obj, "location"),
            notes = text(obj, "description"),
            attendees = strings(obj.optJSONArray("attendees")),
            organizer = text(obj, "organizer"),
            sequence = obj.optInt("sequence", 0),
            rrule = text(obj, "rrule"),
            recurring = obj.optBoolean("recurring", false),
            etag = text(obj, "etag"),
            ical = ical,
            created = text(obj, "created"),
            updated = text(obj, "updated"),
            calendar = text(obj, "calendar")
        )
    }

    private fun parseCalendar(obj: JSONObject): CalendarRecord {
        val hex = text(obj, "hex")
        if (hex.isBlank()) throw AdukiException.Network("Calendar without a hex in the reply")
        val kind = text(obj, "kind")
        val default = kind.equals("default", ignoreCase = true)
        return CalendarRecord(
            hex = hex,
            name = text(obj, "name"),
            description = optional(obj, "description"),
            color = optional(obj, "color"),
            timezone = text(obj, "timezone").ifBlank { "UTC" },
            default = default,
            writable = true,
            href = text(obj, "href"),
            created = text(obj, "created"),
            updated = text(obj, "updated")
        )
    }

    private fun optional(obj: JSONObject, key: String): String? =
        if (obj.isNull(key) || !obj.has(key)) null else obj.optString(key).ifBlank { null }

    private fun parseService(obj: JSONObject): ServiceRecord {
        val hex = text(obj, "hex")
        if (hex.isBlank()) throw AdukiException.Network("Booking page without a hex in the reply")
        val place = obj.optJSONObject("place")
        val remind = if (obj.isNull("remind")) null else obj.optJSONArray("remind")
        return ServiceRecord(
            hex = hex,
            slug = text(obj, "slug"),
            name = text(obj, "name"),
            description = text(obj, "about"),
            lengths = obj.optJSONArray("lengths")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: listOf(30),
            before = obj.optInt("before", 0),
            after = obj.optInt("after", 0),
            notice = obj.optInt("notice", 60),
            horizon = obj.optInt("horizon", 60),
            increment = obj.optInt("step", 30),
            visibility = text(obj, "visibility").ifBlank { "public" },
            confirm = text(obj, "confirm").ifBlank { "auto" },
            place = place?.takeIf { it.length() > 0 }?.let { Place(text(it, "kind"), text(it, "value")) },
            questions = obj.optJSONArray("questions")?.toString() ?: "[]",
            reminders = remind?.let { a -> (0 until a.length()).map { a.optInt(it) } },
            active = obj.isNull("paused") || !obj.has("paused"),
            schedule = text(obj, "schedule"),
            created = text(obj, "created"),
            updated = text(obj, "updated")
        )
    }

    private fun parseOffer(obj: JSONObject): OfferRecord = OfferRecord(
        hex = text(obj, "hex"),
        page = text(obj, "page"),
        slug = text(obj, "slug"),
        name = text(obj, "name"),
        description = text(obj, "about"),
        length = obj.optInt("length", 30),
        before = if (obj.isNull("before")) null else obj.optInt("before"),
        after = if (obj.isNull("after")) null else obj.optInt("after"),
        price = obj.optLong("price", 0),
        currency = text(obj, "currency"),
        questions = obj.optJSONArray("questions")?.toString() ?: "[]",
        active = obj.isNull("paused") || !obj.has("paused")
    )

    private fun parseBooking(obj: JSONObject): BookingRecord {
        val span = obj.optJSONObject("span")
        val place = obj.optJSONObject("place")
        return BookingRecord(
            hex = text(obj, "hex"),
            page = text(obj, "page"),
            start = span?.let { text(it, "start") }.orEmpty(),
            end = span?.let { text(it, "end") }.orEmpty(),
            zone = text(obj, "zone"),
            state = text(obj, "state"),
            uid = text(obj, "uid"),
            sequence = obj.optInt("sequence", 0),
            notes = text(obj, "notes"),
            cancelled = text(obj, "cancelled"),
            reason = text(obj, "reason"),
            created = text(obj, "created"),
            place = place?.let { Place(text(it, "kind"), text(it, "value")) },
            guests = objects(obj.optJSONArray("guests") ?: JSONArray()).map {
                Address(name = text(it, "name").ifBlank { null }, email = text(it, "email"))
            }
        )
    }

    private fun <T> execute(request: Request, transform: (String) -> T): T {
        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw AdukiException.Network("Network request failed for ${request.url}", e)
        }

        response.use { resp ->
            val raw = resp.body?.string() ?: ""
            if (resp.code == 401 || resp.code == 403) {
                val why = Envelope.failure(raw) ?: "HTTP ${resp.code}"
                throw AdukiException.Auth("Unauthorized access ($why)")
            }
            if (!resp.isSuccessful) {
                val why = Envelope.failure(raw) ?: resp.message
                throw AdukiException.Network("HTTP error ${resp.code}: $why", code = resp.code)
            }
            return transform(Envelope.data(raw))
        }
    }

    private companion object {
        const val LIMIT = 200
    }
}
