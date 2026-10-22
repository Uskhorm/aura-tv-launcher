package tv.aura.content.prime

import tv.aura.content.nuvio.MovieMeta
import tv.aura.core.LaunchTarget

object PrimeLinks {
    const val PACKAGE = "com.amazon.firebat"

    /** Catalogue has IMDb ids, not Prime GTIs. Only enable title search after device proof. */
    fun target(meta: MovieMeta): LaunchTarget = LaunchTarget.App(PACKAGE)

    fun searchUri(title: String): String = "https://app.primevideo.com/search?phrase=" +
        java.net.URLEncoder.encode(title, "UTF-8").replace("+", "%20")
}
