package com.feather.browser

import android.content.Context
import android.net.Uri
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

    /**
     * Pattern rules on top of the host list: well-known ad paths on third-party requests,
     * and YouTube's ad endpoints (first-party, so the host list can't touch them).
     */
    fun isBlockedUrl(url: Uri, pageHost: String?): Boolean {
        val host = url.host?.lowercase() ?: return false
        val path = (url.encodedPath ?: "").lowercase()
        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            return path.startsWith("/api/stats/ads") || path.startsWith("/pagead/") ||
                path.startsWith("/ptracking") || path.startsWith("/get_midroll_info")
        }
        if (pageHost == null || registrable(host) == registrable(pageHost.lowercase())) return false
        for (p in PATH_PATTERNS) if (path.contains(p)) return true
        return false
    }

    private fun registrable(h: String): String {
        val parts = h.split('.')
        return if (parts.size <= 2) h else parts.takeLast(2).joinToString(".")
    }

    companion object {
        private val PATH_PATTERNS = arrayOf(
            "/pagead/", "/adserver/", "/adframe", "/ad_frame", "/gampad/", "/adsense/", "/prebid",
            "/adsystem/", "/doubleclick/", "/showads", "/adview"
        )

        private const val YT_SELECTORS =
            "ytd-ad-slot-renderer,ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer," +
            "ytd-promoted-video-renderer,ytd-banner-promo-renderer,#masthead-ad,#player-ads," +
            "ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,.ytp-ad-overlay-container," +
            ".ytd-merch-shelf-renderer"

        /**
         * Runs before any page script (needs a recent WebView). Hides ad containers without a flash,
         * and on YouTube removes ad data from player responses and skips any ad that still appears.
         * Sites in [offHosts] are left alone.
         */
        fun documentStartScript(offHosts: Collection<String>): String {
            val off = org.json.JSONArray(offHosts).toString()
            return """
(function(){try{
var h=location.hostname;
if(($off).indexOf(h)>-1)return;
var css='$COSMETIC_SELECTORS{display:none!important}';
var yt=(h==='youtube.com'||h.endsWith('.youtube.com'));
if(yt)css+='$YT_SELECTORS{display:none!important}';
function addCss(){if(document.getElementById('__fb_css')||!document.documentElement)return;
var s=document.createElement('style');s.id='__fb_css';s.textContent=css;(document.head||document.documentElement).appendChild(s);}
addCss();document.addEventListener('DOMContentLoaded',addCss);
if(yt){
var strip=function(o){if(o&&typeof o==='object'){delete o.adPlacements;delete o.playerAds;delete o.adSlots;
if(o.playerResponse)strip(o.playerResponse);}return o;};
var jp=JSON.parse;JSON.parse=function(){return strip(jp.apply(this,arguments));};
var rj=Response.prototype.json;Response.prototype.json=function(){return rj.apply(this,arguments).then(strip);};
var v;try{Object.defineProperty(window,'ytInitialPlayerResponse',{configurable:true,get:function(){return v;},set:function(x){v=strip(x);}});}catch(e){}
setInterval(function(){
var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button');if(b)b.click();
var c=document.querySelector('.ytp-ad-overlay-close-button');if(c)c.click();
var p=document.querySelector('.html5-video-player.ad-showing video');
if(p&&isFinite(p.duration)&&p.duration>0){p.muted=true;p.currentTime=p.duration;}
},300);
}
}catch(e){}})();
""".trimIndent()
        }

        /** Hides common ad containers so blocked requests don't leave empty frames behind. */
        const val COSMETIC_SELECTORS =
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
