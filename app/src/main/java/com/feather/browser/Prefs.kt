package com.feather.browser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("feather", Context.MODE_PRIVATE)

    var blockAds: Boolean
        get() = sp.getBoolean("block_ads", true)
        set(v) = sp.edit().putBoolean("block_ads", v).apply()

    var httpsOnly: Boolean
        get() = sp.getBoolean("https_only", true)
        set(v) = sp.edit().putBoolean("https_only", v).apply()

    var stripTracking: Boolean
        get() = sp.getBoolean("strip_tracking", true)
        set(v) = sp.edit().putBoolean("strip_tracking", v).apply()

    var blockJs: Boolean
        get() = sp.getBoolean("block_js", false)
        set(v) = sp.edit().putBoolean("block_js", v).apply()

    var clearOnExit: Boolean
        get() = sp.getBoolean("clear_on_exit", false)
        set(v) = sp.edit().putBoolean("clear_on_exit", v).apply()

    var darkPages: Boolean
        get() = sp.getBoolean("dark_pages", false)
        set(v) = sp.edit().putBoolean("dark_pages", v).apply()

    var extraFilters: Boolean
        get() = sp.getBoolean("extra_filters", true)
        set(v) = sp.edit().putBoolean("extra_filters", v).apply()

    var cookieBanners: Boolean
        get() = sp.getBoolean("cookie_banners", true)
        set(v) = sp.edit().putBoolean("cookie_banners", v).apply()

    var appLock: Boolean
        get() = sp.getBoolean("app_lock", false)
        set(v) = sp.edit().putBoolean("app_lock", v).apply()

    // Per-site overrides: {"host": {"js": 0|1|2, "desktop": bool, "dark": bool}}  (js: 0 default, 1 on, 2 off)
    private fun sites(): JSONObject = try { JSONObject(sp.getString("sites", "{}")) } catch (_: Exception) { JSONObject() }

    fun siteJs(host: String): Int = sites().optJSONObject(host)?.optInt("js", 0) ?: 0
    fun siteDesktop(host: String): Boolean = sites().optJSONObject(host)?.optBoolean("desktop", false) ?: false
    fun siteDark(host: String): Boolean = sites().optJSONObject(host)?.optBoolean("dark", false) ?: false

    fun setSite(host: String, key: String, value: Any) {
        val all = sites()
        val o = all.optJSONObject(host) ?: JSONObject()
        o.put(key, value)
        if (o.optInt("js", 0) == 0 && !o.optBoolean("desktop", false) && !o.optBoolean("dark", false)) all.remove(host)
        else all.put(host, o)
        sp.edit().putString("sites", all.toString()).apply()
    }

    var restoreTabs: Boolean
        get() = sp.getBoolean("restore_tabs", false)
        set(v) = sp.edit().putBoolean("restore_tabs", v).apply()

    /** Open tabs as a JSON array of {t,u} plus the selected index; empty when nothing is saved. */
    fun saveSession(tabs: List<Triple<String, String, String>>, index: Int) {
        val arr = JSONArray()
        for ((t, u, g) in tabs) arr.put(JSONObject().put("t", t).put("u", u).put("g", g))
        sp.edit().putString("session", arr.toString()).putInt("session_index", index).apply()
    }

    fun loadSession(): Pair<List<Triple<String, String, String>>, Int> {
        val out = ArrayList<Triple<String, String, String>>()
        try {
            val arr = JSONArray(sp.getString("session", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Triple(o.getString("t"), o.getString("u"), o.optString("g", "")))
            }
        } catch (_: Exception) {
        }
        return out to sp.getInt("session_index", 0)
    }

    fun clearSession() = sp.edit().remove("session").remove("session_index").apply()

    // Sites the user chose to load over plain HTTP permanently
    fun httpAllowed(): MutableList<String> = sp.getStringSet("http_ok", emptySet())!!.sorted().toMutableList()

    fun isHttpAllowed(host: String): Boolean = sp.getStringSet("http_ok", emptySet())!!.contains(host)

    fun setHttpAllowed(host: String, allowed: Boolean) {
        val s = HashSet(sp.getStringSet("http_ok", emptySet())!!)
        if (allowed) s.add(host) else s.remove(host)
        sp.edit().putStringSet("http_ok", s).apply()
    }

    fun allowedHosts(): Set<String> = HashSet(sp.getStringSet("allow", emptySet())!!)

    /** True while a session is open and "clear on exit" has not run yet (covers killed processes). */
    var pendingClear: Boolean
        get() = sp.getBoolean("pending_clear", false)
        set(v) = sp.edit().putBoolean("pending_clear", v).apply()

    var engine: Int
        get() = sp.getInt("engine", 0).coerceIn(0, ENGINES.size - 1)
        set(v) = sp.edit().putInt("engine", v).apply()

    var totalBlocked: Long
        get() = sp.getLong("total_blocked", 0L)
        set(v) = sp.edit().putLong("total_blocked", v).apply()

    // Sites where blocking is switched off
    fun isAllowed(host: String): Boolean = sp.getStringSet("allow", emptySet())!!.contains(host)

    fun setAllowed(host: String, allowed: Boolean) {
        val s = HashSet(sp.getStringSet("allow", emptySet())!!)
        if (allowed) s.add(host) else s.remove(host)
        sp.edit().putStringSet("allow", s).apply()
    }

    // Bookmarks: stored as a JSON array of {t, u}
    fun bookmarks(): MutableList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val arr = JSONArray(sp.getString("bookmarks", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(o.getString("t") to o.getString("u"))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun saveBookmarks(list: List<Pair<String, String>>) {
        val arr = JSONArray()
        for ((t, u) in list) arr.put(JSONObject().put("t", t).put("u", u))
        sp.edit().putString("bookmarks", arr.toString()).apply()
    }

    companion object {
        val ENGINE_NAMES = arrayOf("DuckDuckGo", "Brave Search", "Startpage", "Google")
        val ENGINES = arrayOf(
            "https://duckduckgo.com/?q=%s",
            "https://search.brave.com/search?q=%s",
            "https://www.startpage.com/do/dsearch?query=%s",
            "https://www.google.com/search?q=%s",
        )
    }
}
