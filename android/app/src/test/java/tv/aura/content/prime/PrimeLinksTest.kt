package tv.aura.content.prime

import org.junit.Assert.*
import org.junit.Test
import tv.aura.content.nuvio.MovieMeta
import tv.aura.core.LaunchTarget

class PrimeLinksTest {
    @Test fun searchTitleCannotInjectQueryParametersAndSupportsHindi() {
        assertEquals("https://app.primevideo.com/search?phrase=A%26B%20%231", PrimeLinks.searchUri("A&B #1"))
        assertTrue(PrimeLinks.searchUri("भारत").contains("%E0%A4"))
    }

    @Test fun imdbIdIsNeverPretendedToBeAnAmazonContentId() {
        assertEquals(LaunchTarget.App("com.amazon.firebat"), PrimeLinks.target(MovieMeta("tt1190634", "series", "The Boys")))
        assertTrue(PrimeHeroProvider.BASE.contains("/GB_en_amp"))
        assertEquals("GB_en_jw_tnd_amp", PrimeHeroProvider.CATALOG)
    }
}
