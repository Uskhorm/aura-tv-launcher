package tv.aura.content.nuvio

import org.junit.Assert.*
import org.junit.Test

class PremiumMovieArtTest {
    @Test fun rejectsOpaqueBlankAndNearlyInvisibleLogos() {
        assertFalse(PremiumMovieArt.alphaEligible(10000, 0, 10000))
        assertFalse(PremiumMovieArt.alphaEligible(10000, 10000, 0))
        assertFalse(PremiumMovieArt.alphaEligible(10000, 9900, 100))
        assertFalse(PremiumMovieArt.alphaEligible(0, 0, 0))
        assertTrue(PremiumMovieArt.alphaEligible(10000, 7000, 2000))
    }
}
