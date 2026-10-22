package tv.aura.content.youtube

import org.junit.Assert.*
import org.junit.Test

class CinematicPolicyTest {
    @Test fun scriptsAndImageBounds() {
        assertTrue(YouTubeFeedParser.isLatinTitle("Dune: Part Three | Official Trailer – 4K"))
        assertTrue(YouTubeFeedParser.isLatinTitle("Café • Earth 🌍"))
        assertFalse(YouTubeFeedParser.isLatinTitle("Trailer हिन्दी"))
        assertFalse(YouTubeFeedParser.isLatinTitle("Trailer 日本語"))
        assertFalse(YouTubeMaxres.isRealMaxres(120, 90))
        assertFalse(YouTubeMaxres.isRealMaxres(480, 360))
        assertFalse(YouTubeMaxres.isRealMaxres(1280, 960))
        assertTrue(YouTubeMaxres.isRealMaxres(1280, 720))
        assertTrue(YouTubeMaxres.isRealMaxres(1920, 1080))
    }
    @Test fun resolverUsesOwnerAndRejectsForeignHosts() {
        val owner = "UCWOA1ZGywLbqmigxE4Qlvuw"
        assertEquals(owner, YouTubeChannelResolver.channelIdFromHtml("""{"externalId": "$owner"}"""))
        assertEquals("/@Netflix", YouTubeChannelResolver.channelPath("https://www.youtube.com/@Netflix/videos"))
        assertNull(YouTubeChannelResolver.channelPath("https://youtube.com.evil.test/@Netflix"))
        assertNull(YouTubeChannelResolver.channelPath("https://youtube.com:443/@Netflix"))
    }
}
