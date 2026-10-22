package tv.aura.content.legacy

import tv.aura.home.R
import tv.aura.app.*
import tv.aura.design.*
import tv.aura.system.*
import tv.aura.ui.hero.*
import tv.aura.ui.dock.*
import tv.aura.ui.control.*
import tv.aura.ui.settings.*
import tv.aura.ui.status.*
import tv.aura.ui.ambient.*

/** One page of the Top Shelf carousel for an app. */
class Slide(val photo: Int, val kicker: String, val title: String, val body: String)

/**
 * Top Shelf pages per app. Android doesn't let third-party launchers read other apps' rows,
 * so each well-known app gets a few hand-written sections and a matching set of photos.
 */
object Catalog {
    private class Shelf(val name: String?, val photos: List<Int>, val pages: List<Pair<String, String>>)

    private val VIDEO = listOf(
        "Watch Now" to "Pick up where you left off, or find something new.",
        "Trending" to "See what everyone is watching this week.",
        "Your Library" to "Everything you’ve saved, in one place.",
    )
    private val MEDIA = listOf(
        "Library" to "Your movies, shows and music, streamed from home.",
        "Continue Watching" to "Jump straight back into what you were watching.",
        "Recently Added" to "The newest additions to your collection.",
    )

    private val shelves = mapOf(
        "com.google.android.youtube.tv" to Shelf("YouTube", listOf(6, 1, 11), listOf(
            "Recommended" to "Videos picked for you, ready whenever you are.",
            "Subscriptions" to "The latest from the channels you follow.",
            "Live" to "Streams and premieres happening right now.",
        )),
        "com.netflix.ninja" to Shelf("Netflix", listOf(4, 17, 12), listOf(
            "Continue Watching" to "Pick up right where you left off.",
            "New & Popular" to "See what everyone is watching this week.",
            "My List" to "Everything you’ve saved for later.",
        )),
        "com.amazon.amazonvideo.livingroom" to Shelf("Prime Video", listOf(3, 0, 10), listOf(
            "Watch Now" to "Movies, series and live sport in one place.",
            "Originals" to "Shows you’ll only find on Prime Video.",
            "Live TV" to "Sport and channels, streaming live.",
        )),
        "com.disney.disneyplus" to Shelf("Disney+", listOf(8, 13, 2), listOf(
            "Featured" to "Stories from Disney, Pixar, Marvel, Star Wars and more.",
            "Continue Watching" to "Jump back into your favourites.",
            "Explore" to "Nature and adventure from National Geographic.",
        )),
        "com.apple.atve.androidtv.appletv" to Shelf("Apple TV", listOf(12, 16, 14), listOf(
            "Up Next" to "Your shows and movies, all in one place.",
            "Apple TV+" to "Apple Originals, only on Apple TV+.",
            "Store" to "Buy or rent the latest movies.",
        )),
        "com.wbd.stream" to Shelf("Max", listOf(17, 4, 9), listOf(
            "Continue Watching" to "Pick up where you left off.",
            "New on Max" to "The latest series and blockbusters.",
            "HBO" to "Iconic series and films from HBO.",
        )),
        "com.spotify.tv.android" to Shelf("Spotify", listOf(14, 5, 11), listOf(
            "Made for You" to "Mixes and playlists tuned to your taste.",
            "Podcasts" to "New episodes from the shows you follow.",
            "Your Library" to "Saved songs, albums and artists.",
        )),
        "tv.twitch.android.app" to Shelf("Twitch", listOf(10, 8, 17), listOf(
            "Live Channels" to "Creators streaming right now.",
            "Following" to "The channels you follow, all in one place.",
            "Browse" to "Games, music, art and more.",
        )),
        "com.google.android.youtube.tvmusic" to Shelf("YouTube Music", listOf(16, 14, 5), listOf(
            "Quick Picks" to "Songs to start your session.",
            "Mixed for You" to "Mixes built around what you love.",
            "Library" to "Your playlists, albums and songs.",
        )),
        "com.plexapp.android" to Shelf("Plex", listOf(15, 13, 0), MEDIA),
        "org.jellyfin.androidtv" to Shelf("Jellyfin", listOf(9, 15, 2), MEDIA),
        "org.xbmc.kodi" to Shelf("Kodi", listOf(10, 1, 12), MEDIA),
        "org.videolan.vlc" to Shelf("VLC", listOf(3, 6, 16), MEDIA),
        "tv.pluto.android" to Shelf("Pluto TV", listOf(11, 7, 3), listOf(
            "Live TV" to "Hundreds of free channels, streaming now.",
            "On Demand" to "Free movies and series, any time.",
        )),
        "com.android.vending" to Shelf("Google Play", listOf(1, 13, 7), listOf(
            "Apps & Games" to "Discover something new for your TV.",
            "Updates" to "Keep your apps fresh and secure.",
        )),
        "com.android.tv.settings" to Shelf("Settings", listOf(9, 0), listOf(
            "Settings" to "Network, display, sound, accounts and more.",
        )),
        SETTINGS_KEY to Shelf("Home", listOf(12, 4, 8), listOf(
            "Make it Yours" to "Top Shelf, screensaver, clock, weather and sounds.",
            "Aerial Screensaver" to "Breathtaking places, drifting across your screen.",
            "Rearrange" to "Hold OK on any app to move it. Press ≡ for more options.",
        )),
    )

    fun slides(app: AppEntry): List<Slide> {
        val s = shelves[app.key]
        if (s != null) return s.pages.mapIndexed { k, (t, b) -> Slide(s.photos[k % s.photos.size], (s.name ?: app.label).uppercase(), t, b) }
        val seed = app.key.hashCode().let { if (it < 0) -it else it }
        val n = Photos.all.size
        val photos = listOf(seed % n, (seed / 7 + 5) % n, (seed / 13 + 11) % n)
        val pages = if (app.category == "Watch") VIDEO else listOf(
            app.label to "Open ${app.label} right from your Home screen.",
            "Ready When You Are" to "Press OK to jump in.",
        )
        return pages.mapIndexed { k, (t, b) -> Slide(photos[k % photos.size], (app.category ?: "App").uppercase().let { if (k == 0 && t == app.label) it else app.label.uppercase() }, t, b) }
    }
}
