package com.feather.browser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ad/tracker blocker.
 *  - A sorted, byte-packed host list (binary search, ~3 MB instead of ~15 MB as a HashSet).
 *  - Host-anchored URL rules from EasyList/EasyPrivacy/uBO (with exceptions and third-party flags).
 *  - Built-in path patterns and YouTube ad endpoints.
 *  - Cosmetic filtering: generic selectors + per-site selectors.
 * All data is loaded on one background thread; until it finishes nothing is blocked.
 */
class AdBlocker(context: Context) {

    private class HostSet(val data: ByteArray, val starts: IntArray, val ends: IntArray) {
        fun contains(h: String): Boolean {
            var lo = 0
            var hi = starts.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val c = cmp(mid, h)
                if (c == 0) return true
                if (c < 0) lo = mid + 1 else hi = mid - 1
            }
            return false
        }

        private fun cmp(i: Int, h: String): Int {
            val s = starts[i]
            val len = ends[i] - s
            val n = minOf(len, h.length)
            for (k in 0 until n) {
                val a = data[s + k].toInt() and 0xFF
                val b = h[k].code
                if (a != b) return a - b
            }
            return len - h.length
        }
    }

    private class Rule(val pat: String, val thirdParty: Boolean)

    private val app = context.applicationContext

    @Volatile private var hosts: HostSet? = null
    @Volatile private var blockRules: HashMap<String, ArrayList<Rule>> = HashMap()
    @Volatile private var allowRules: HashMap<String, ArrayList<Rule>> = HashMap()
    @Volatile private var domainCosmetic: HashMap<String, ArrayList<String>> = HashMap()
    @Volatile var genericCss: String = ""
        private set

    /** Turns EasyList-style rules and cosmetic selectors on or off (the host list always applies). */
    @Volatile var extraEnabled: Boolean = true

    /** Called on the loader thread when everything is ready. */
    @Volatile var onLoaded: (() -> Unit)? = null

    fun start() {
        Thread {
            try {
                loadHosts()
                loadRules()
                loadCosmetic()
            } catch (_: Exception) {
                // Whatever failed stays empty; browsing still works.
            }
            onLoaded?.invoke()
        }.apply { name = "feather-filters"; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun loadHosts() {
        val data = app.assets.open("blocklist.txt").use { it.readBytes() }
        var count = 0
        for (b in data) if (b == '\n'.code.toByte()) count++
        val starts = IntArray(count)
        val ends = IntArray(count)
        var n = 0
        var s = 0
        for (i in data.indices) {
            if (data[i] == '\n'.code.toByte()) {
                if (i > s) {
                    starts[n] = s
                    ends[n] = i
                    n++
                }
                s = i + 1
            }
        }
        hosts = HostSet(data, starts.copyOf(n), ends.copyOf(n))
    }

    private fun loadRules() {
        val block = HashMap<String, ArrayList<Rule>>()
        val allow = HashMap<String, ArrayList<Rule>>()
        app.assets.open("net_rules.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                val p = line.split('\t')
                if (p.size < 3) continue
                val rule = Rule(p[1], p[2].contains('t'))
                val map = if (p[2].contains('e')) allow else block
                map.getOrPut(p[0]) { ArrayList(2) }.add(rule)
            }
        }
        blockRules = block
        allowRules = allow
    }

    private fun loadCosmetic() {
        val gen = StringBuilder()
        app.assets.open("cosmetic_generic.txt").bufferedReader().useLines { lines ->
            for (l in lines) if (l.isNotBlank()) gen.append(l).append("{display:none!important}\n")
        }
        genericCss = gen.toString()
        val dom = HashMap<String, ArrayList<String>>()
        app.assets.open("cosmetic_domain.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                val t = line.indexOf('\t')
                if (t < 0) continue
                val sel = line.substring(t + 1)
                for (h in line.substring(0, t).split(',')) dom.getOrPut(h) { ArrayList(2) }.add(sel)
            }
        }
        domainCosmetic = dom
    }

    // ------------------------------------------------------------------ decisions

    private fun hostListed(host: String): Boolean {
        val set = hosts ?: return false
        var h = host.lowercase()
        while (true) {
            if (set.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun isYoutubeAdEndpoint(url: Uri): Boolean {
        val host = url.host?.lowercase() ?: return false
        if (host != "youtube.com" && !host.endsWith(".youtube.com")) return false
        val path = (url.encodedPath ?: "").lowercase()
        return path.startsWith("/api/stats/ads") || path.startsWith("/pagead/") ||
            path.startsWith("/ptracking") || path.startsWith("/get_midroll_info")
    }

    fun shouldBlock(url: Uri, pageHost: String?): Boolean {
        val host = url.host?.lowercase() ?: return false
        if (isYoutubeAdEndpoint(url)) return true
        val thirdParty = pageHost != null && registrable(host) != registrable(pageHost.lowercase())
        var blocked = hostListed(host)
        if (!blocked && thirdParty) {
            val path = (url.encodedPath ?: "").lowercase()
            for (p in PATH_PATTERNS) if (path.contains(p)) { blocked = true; break }
        }
        if (!blocked && extraEnabled && blockRules.isNotEmpty()) blocked = ruleMatches(blockRules, host, url, thirdParty)
        if (blocked && extraEnabled && allowRules.isNotEmpty() && ruleMatches(allowRules, host, url, thirdParty)) return false
        return blocked
    }

    private fun ruleMatches(map: HashMap<String, ArrayList<Rule>>, host: String, url: Uri, thirdParty: Boolean): Boolean {
        var h = host
        var rest: String? = null
        while (true) {
            val rules = map[h]
            if (rules != null) {
                if (rest == null) {
                    val q = url.encodedQuery
                    rest = (url.encodedPath ?: "/").lowercase() + (if (q != null) "?" + q.lowercase() else "")
                }
                for (r in rules) {
                    if (r.thirdParty && !thirdParty) continue
                    if (r.pat.isEmpty() || globPrefix(r.pat, 0, rest, 0)) return true
                }
            }
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** Adblock-style pattern at the start of [s]: '*' = anything, '^' = a separator or the end. */
    private fun globPrefix(p: String, pi0: Int, s: String, si0: Int): Boolean {
        var pi = pi0
        var si = si0
        while (pi < p.length) {
            val c = p[pi]
            when {
                c == '*' -> {
                    var k = si
                    while (k <= s.length) {
                        if (globPrefix(p, pi + 1, s, k)) return true
                        k++
                    }
                    return false
                }
                c == '^' -> {
                    if (si == s.length) { pi++; continue }
                    val ch = s[si]
                    if (ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.' || ch == '%') return false
                    si++
                    pi++
                }
                si < s.length && s[si] == c -> { si++; pi++ }
                else -> return false
            }
        }
        return true
    }

    private fun registrable(h: String): String {
        val parts = h.split('.')
        return if (parts.size <= 2) h else parts.takeLast(2).joinToString(".")
    }

    /** CSS for per-site element hiding rules that apply to [host] (and its parent domains). */
    fun domainCss(host: String): String {
        if (!extraEnabled) return ""
        val map = domainCosmetic
        if (map.isEmpty()) return ""
        val sb = StringBuilder()
        var h = host.lowercase()
        while (true) {
            map[h]?.let { for (sel in it) sb.append(sel).append("{display:none!important}\n") }
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        return sb.toString()
    }

    companion object {
        private val PATH_PATTERNS = arrayOf(
            "/pagead/", "/adserver/", "/adframe", "/ad_frame", "/gampad/", "/adsense/", "/prebid",
            "/adsystem/", "/doubleclick/", "/showads", "/adview"
        )

        /** Hides common ad containers so blocked requests don't leave empty frames behind. */
        const val COSMETIC_SELECTORS =
            "ins.adsbygoogle,.adsbygoogle,iframe[id^=\"google_ads_iframe\"],div[id^=\"google_ads_iframe\"]," +
            "div[id^=\"div-gpt-ad\"],[data-google-query-id],[data-ad-slot],.ad-slot,.ad-container,.ad-banner," +
            ".ad-wrapper,.advert,.advertisement,.sponsored-ad,#ad-container,#ad_banner,[id^=\"taboola-\"]," +
            ".taboola,.OUTBRAIN,[id^=\"outbrain_widget\"],iframe[src*=\"doubleclick.net\"]," +
            "iframe[src*=\"googlesyndication.com\"],a[href*=\"doubleclick.net/\"]"

        /** Fallback for old WebViews without document-start scripts. */
        const val COSMETIC_JS =
            "(function(){if(document.getElementById('__fb_css')||!document.documentElement)return;" +
            "var s=document.createElement('style');s.id='__fb_css';" +
            "s.textContent='" + COSMETIC_SELECTORS + "{display:none!important}';" +
            "(document.head||document.documentElement).appendChild(s);})();"

        private const val YT_SELECTORS =
            "ytd-ad-slot-renderer,ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer," +
            "ytd-promoted-video-renderer,ytd-banner-promo-renderer,#masthead-ad,#player-ads," +
            "ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,.ytp-ad-overlay-container," +
            ".ytd-merch-shelf-renderer"

        private const val COOKIE_SELECTORS =
            "#onetrust-banner-sdk,#onetrust-consent-sdk,#CybotCookiebotDialog,#CybotCookiebotDialogBodyUnderlay," +
            ".cc-window,.cc-banner,#cookie-banner,#cookiebanner,.cookie-banner,#cookie-notice,.cookie-notice," +
            "#cookie-consent,.cookie-consent,#gdpr-banner,.gdpr-banner,#truste-consent-track,.fc-consent-root," +
            "#didomi-host,.qc-cmp2-container,[id^=\"sp_message_container\"],.osano-cb-dialog,#usercentrics-root," +
            "#cmpbox,#cmpwrapper"

        /**
         * Runs before any page script (needs a recent WebView). Hides ad containers without a flash,
         * hides and declines cookie banners, and on YouTube removes ad data from player responses and
         * skips any ad that still appears. Sites in [offHosts] are left alone.
         */
        fun documentStartScript(offHosts: Collection<String>, genericCss: String, cookieBanners: Boolean): String {
            val off = JSONArray(offHosts).toString()
            val generic = JSONObject.quote(genericCss)
            val cookies = if (cookieBanners) COOKIE_JS else ""
            return """
(function(){try{
var h=location.hostname;
if(($off).indexOf(h)>-1)return;
var css='$COSMETIC_SELECTORS{display:none!important}';
css+=$generic;
var yt=(h==='youtube.com'||h.endsWith('.youtube.com'));
if(yt)css+='$YT_SELECTORS{display:none!important}';
function addCss(){if(document.getElementById('__fb_css')||!document.documentElement)return;
var s=document.createElement('style');s.id='__fb_css';s.textContent=css;(document.head||document.documentElement).appendChild(s);}
$cookies
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
},100);
}
}catch(e){}})();
""".trimIndent()
        }

        private val COOKIE_JS = """
var CK='$COOKIE_SELECTORS';
css+=CK+'{display:none!important}';
function cookieStart(){
var SEL=['#onetrust-reject-all-handler','#CybotCookiebotDialogBodyButtonDecline','.cc-deny','.fc-cta-do-not-consent','#didomi-notice-disagree-button','.osano-cm-denyAll','button[data-testid="uc-deny-all-button"]'];
var re=/^(reject( all)?|decline( all)?|deny( all)?|refuse( all)?|only (necessary|essential)( cookies)?|necessary only|essential only|continue without accepting|disagree|no,? thanks)/i;
function tryReject(){
for(var i=0;i<SEL.length;i++){var e=document.querySelector(SEL[i]);if(e){e.click();return true;}}
var boxes=document.querySelectorAll(CK);
for(var j=0;j<boxes.length;j++){var bs=boxes[j].querySelectorAll('button,a,[role=button]');
for(var k=0;k<bs.length;k++){var t=(bs[k].innerText||bs[k].value||'').trim();if(re.test(t)){bs[k].click();return true;}}}
return false;}
var tries=0;var iv=setInterval(function(){
var found=document.querySelector(CK);
if(found&&tryReject()||++tries>20){clearInterval(iv);
if(found){setTimeout(function(){document.documentElement.style.setProperty('overflow','auto','important');
if(document.body)document.body.style.setProperty('overflow','auto','important');},400);}}},500);}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',cookieStart);else cookieStart();
""".trimIndent()
    }
}
