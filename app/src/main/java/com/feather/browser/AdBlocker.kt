package com.feather.browser

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host-based ad/tracker blocker. The list is loaded once on a background thread
 * into a HashSet; lookups walk up the domain (ads.example.com -> example.com).
 */
class AdBlocker(context: Context) {

    @Volatile
    private var hosts: HashSet<String> = HashSet()
    private val started = AtomicBoolean(false)

    init {
        val app = context.applicationContext
        if (started.compareAndSet(false, true)) {
            Thread {
                try {
                    val set = HashSet<String>(65536)
                    app.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val h = line.trim()
                            if (h.isNotEmpty() && h[0] != '#') set.add(h)
                        }
                    }
                    hosts = set
                } catch (_: Exception) {
                    // Blocking stays off if the list can't be read; browsing still works.
                }
            }.apply { name = "feather-blocklist"; priority = Thread.MIN_PRIORITY }.start()
        }
    }

    fun isBlocked(host: String): Boolean {
        val set = hosts
        if (set.isEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (set.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    companion object {
        /** Hides common ad containers so blocked requests don't leave empty frames behind. */
        private const val COSMETIC_SELECTORS =
            "ins.adsbygoogle,.adsbygoogle,iframe[id^=\"google_ads_iframe\"],div[id^=\"google_ads_iframe\"]," +
            "div[id^=\"div-gpt-ad\"],[data-google-query-id],[data-ad-slot],.ad-slot,.ad-container,.ad-banner," +
            ".ad-wrapper,.advert,.advertisement,.sponsored-ad,#ad-container,#ad_banner,[id^=\"taboola-\"]," +
            ".taboola,.OUTBRAIN,[id^=\"outbrain_widget\"],iframe[src*=\"doubleclick.net\"]," +
            "iframe[src*=\"googlesyndication.com\"],a[href*=\"doubleclick.net/\"]"

        const val COSMETIC_JS =
            "(function(){if(document.getElementById('__fb_css')||!document.documentElement)return;" +
            "var s=document.createElement('style');s.id='__fb_css';" +
            "s.textContent='" + COSMETIC_SELECTORS + "{display:none!important}';" +
            "(document.head||document.documentElement).appendChild(s);})();"
    }
}
