package tv.aura.content.sports

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SportsFixture(
    val id: String,
    val title: String,
    val league: String,
    val sport: String,
    val startsAtMs: Long?,
    val status: String,
    val backdropUrl: String?,
    val venue: String?,
)

object SportsFeedParser {
    val ukZone: ZoneId = ZoneId.of("Europe/London")
    private val liveStatuses = setOf("1h", "2h", "ht", "et", "bt", "p", "live", "in progress", "inprogress", "playing", "innings break")
    private val endedStatuses = setOf("ft", "aet", "pen", "finished", "match finished", "completed", "cancelled", "canceled", "postponed", "abandoned", "suspended")

    fun parse(json: String): List<SportsFixture> {
        require(json.length <= 512 * 1024)
        val root = JSONObject(json)
        require(root.has("events")) { "Not an events response" }
        val events = root.optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).mapNotNull { index ->
            val event = events.optJSONObject(index) ?: return@mapNotNull null
            val id = event.text("idEvent") ?: return@mapNotNull null
            if (!Regex("[0-9]{1,20}").matches(id)) return@mapNotNull null
            val title = event.text("strEvent") ?: return@mapNotNull null
            val start = parseStart(event.text("strTimestamp"), event.text("dateEvent"), event.text("strTime"))
            val art = listOf("strThumb", "strFanart", "strBanner").firstNotNullOfOrNull { field ->
                event.text(field)?.takeIf { validArtwork(it) }
            }
            SportsFixture(id, title, event.text("strLeague").orEmpty(), event.text("strSport").orEmpty(), start,
                event.text("strStatus").orEmpty(), art, event.text("strVenue"))
        }
    }

    fun parseStart(timestamp: String?, date: String?, time: String?): Long? {
        if (timestamp != null) {
            runCatching { Instant.parse(timestamp).toEpochMilli() }.getOrNull()?.let { return it }
            runCatching { LocalDateTime.parse(timestamp).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()?.let { return it }
            runCatching { java.time.OffsetDateTime.parse(timestamp).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        }
        // Missing kick-off stays unknown. Never label an unknown time as midnight.
        if (date == null || time == null) return null
        return runCatching { LocalDateTime.parse("${date}T${time.removeSuffix("Z")}").toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    fun select(events: List<SportsFixture>, focus: List<String>, nowMs: Long): List<SportsFixture> {
        val enabled = focus.map { it.lowercase(Locale.ROOT) }.toSet()
        return events.asSequence().distinctBy { it.id }.filter { event ->
            val sport = event.sport.lowercase(Locale.ROOT)
            val wanted = (sport == "cricket" && "cricket" in enabled && relevantCricket(event)) ||
                (sport in setOf("soccer", "football") && "football" in enabled &&
                    (event.league.contains("Premier League", true) || event.league.contains("Champions League", true)))
            val start = event.startsAtMs
            wanted && event.status.lowercase(Locale.ROOT) !in endedStatuses &&
                (start == null || start >= nowMs - 12 * 60 * 60_000L) &&
                (start == null || start <= nowMs + 45L * 24 * 60 * 60_000)
        }.sortedWith(compareByDescending<SportsFixture> { isLive(it, nowMs) }
            .thenBy { it.startsAtMs ?: Long.MAX_VALUE }).take(10).toList()
    }

    fun isLive(event: SportsFixture, nowMs: Long): Boolean = event.status.lowercase(Locale.ROOT) in liveStatuses &&
        event.startsAtMs?.let { nowMs - it in 0..12 * 60 * 60_000L } == true

    fun badge(event: SportsFixture, nowMs: Long, allowLive: Boolean = true): String? {
        if (allowLive && isLive(event, nowMs)) return "LIVE"
        val start = event.startsAtMs?.let { Instant.ofEpochMilli(it).atZone(ukZone) } ?: return "UPCOMING"
        val today = Instant.ofEpochMilli(nowMs).atZone(ukZone).toLocalDate()
        val clock = start.format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK))
        return when (start.toLocalDate()) {
            today -> "TODAY $clock"
            today.plusDays(1) -> "TOMORROW $clock"
            else -> start.format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.UK)).uppercase(Locale.UK)
        }
    }

    private fun relevantCricket(event: SportsFixture) = event.title.contains("India", true) ||
        event.league.contains("Indian Premier", true) || event.league.contains("ICC", true) || event.league.contains("World Cup", true)

    private fun validArtwork(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.scheme == "https" && uri.host != null && uri.userInfo == null &&
            (uri.host == "thesportsdb.com" || uri.host.endsWith(".thesportsdb.com"))
    }.getOrDefault(false)
    private fun JSONObject.text(key: String) = optString(key).takeIf { it.isNotBlank() && it != "null" }
}
