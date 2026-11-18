package app.couchtv.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Xml
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The Space look's news line: headlines from SVT's own RSS feeds (news, Stockholm, sport, culture
 * and entertainment), mixed so the categories take turns, and a small reader that opens a headline's
 * article full screen inside the launcher.
 *
 * The page cannot choose what is fetched: the feeds are fixed here, and the reader only opens
 * https links on svt.se. fetch() answers later through newsReply(json), so the page never waits on
 * the network.
 */
object News {
    private val FEEDS = listOf(
        "nyheter" to "https://www.svt.se/nyheter/rss.xml",
        "sport" to "https://www.svt.se/sport/rss.xml",
        "stockholm" to "https://www.svt.se/nyheter/lokalt/stockholm/rss.xml",
        "noje" to "https://www.svt.se/kultur/rss.xml"
    )
    private const val PER_FEED = 8

    fun allowed(url: String): Boolean = try {
        val u = Uri.parse(url)
        u.scheme == "https" && (u.host == "www.svt.se" || u.host == "svt.se")
    } catch (e: Exception) { false }

    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) couch-tv")
        c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    } catch (e: Exception) { null }

    /** title and link of each item in an RSS feed, newest first as the feed lists them */
    private fun items(xml: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val p = Xml.newPullParser()
            p.setInput(xml.reader())
            var inItem = false; var title = ""; var link = ""; var tag = ""
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> { tag = p.name; if (tag == "item") { inItem = true; title = ""; link = "" } }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inItem) {
                        if (tag == "title") title += p.text
                        else if (tag == "link") link += p.text
                    }
                    XmlPullParser.END_TAG -> {
                        if (p.name == "item") {
                            inItem = false
                            val t = title.trim().replace(Regex("\\s+"), " ")
                            if (t.isNotEmpty() && allowed(link.trim())) out.add(t.take(160) to link.trim())
                        }
                        tag = ""
                    }
                }
                p.next()
            }
        } catch (e: Exception) {}
        return out
    }

    /** every feed's headlines, the categories taking turns: news, sport, Stockholm, culture, news ... */
    fun mixed(): JSONArray {
        val lists = FEEDS.map { (cat, url) -> cat to (get(url)?.let { items(it) } ?: emptyList()).take(PER_FEED) }
        val seen = HashSet<String>()
        val out = JSONArray()
        for (i in 0 until PER_FEED) for ((cat, l) in lists) {
            val it = l.getOrNull(i) ?: continue
            if (!seen.add(it.second)) continue   // the same story in two feeds shows once
            out.put(JSONObject().put("t", it.first).put("l", it.second).put("c", cat))
        }
        return out
    }

    // ---- Live TV: SVT's five channels, the picture from SVT's own player API, the guide from SVT Play ----
    val CHANNELS = listOf("svt1", "svt2", "barnkanalen", "kunskapskanalen", "svt24")

    /** the channel's HLS addresses (hls first, then hls-full), only from SVT's live CDN */
    fun stream(ch: String): JSONObject {
        val out = JSONObject().put("ch", ch)
        if (ch !in CHANNELS) return out
        val body = get("https://api.svt.se/video/ch-$ch") ?: return out
        try {
            val refs = JSONObject(body).optJSONArray("videoReferences") ?: return out
            val by = HashMap<String, String>()
            for (i in 0 until refs.length()) {
                val r = refs.optJSONObject(i) ?: continue
                val u = r.optString("url")
                val host = try { Uri.parse(u).host ?: "" } catch (e: Exception) { "" }
                if (u.startsWith("https://") && (host.endsWith(".akamaized.net") || host.endsWith(".svt.se"))) by[r.optString("format")] = u
            }
            val list = JSONArray()
            listOf("hls", "hls-full", "hls-cmaf-live").forEach { f -> by[f]?.let { list.put(it) } }
            out.put("urls", list)
        } catch (e: Exception) {}
        return out
    }

    private fun unescape(t: String): String = t.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()

    /**
     * What is on now and next on each channel, read from SVT Play's channel page, where every entry is
     * written "Klockan 18:47 - Sverige Live" and each channel's list ends with a link holding
     * expand=ch-<channel>. A page that changes its wording only leaves the guide empty.
     */
    fun guide(): JSONObject {
        val out = JSONObject()
        val html = get("https://www.svtplay.se/kanaler/svt1?start=auto") ?: return out
        val marks = Regex("expand=ch-([a-z0-9]+)").findAll(html).toList()
        val entry = Regex("Klockan (\\d{1,2})[:.](\\d{2}) [-–] ([^<\"]{1,140})")
        var from = 0
        for (m in marks) {
            val ch = m.groupValues[1]
            val seg = html.substring(from, m.range.first)
            from = m.range.last
            if (ch !in CHANNELS || out.has(ch)) continue
            val list = JSONArray()
            val seen = HashSet<String>()
            for (e in entry.findAll(seg)) {
                val time = e.groupValues[1].padStart(2, '0') + ":" + e.groupValues[2]
                val name = unescape(e.groupValues[3]).replace(Regex("\\d{1,2}[:.]\\d{2}$"), "").trim()
                if (name.isEmpty() || !seen.add(time + name)) continue
                list.put(JSONObject().put("t", time).put("n", name.take(80)))
                if (list.length() >= 4) break
            }
            if (list.length() > 0) out.put(ch, list)
        }
        return out
    }

    class Page(private val ctx: Activity, private val web: WebView) {
        private val main = Handler(Looper.getMainLooper())
        private val io = Executors.newSingleThreadExecutor()

        @JavascriptInterface
        fun fetch() {
            io.execute {
                val json = try { mixed().toString() } catch (e: Exception) { "[]" }
                main.post { try { web.evaluateJavascript("window.newsReply && newsReply($json)", null) } catch (e: Exception) {} }
            }
        }

        private fun reply(fn: String, json: String) {
            main.post { try { web.evaluateJavascript("window.$fn && $fn($json)", null) } catch (e: Exception) {} }
        }

        /** answers through liveStream({ch, urls}) */
        @JavascriptInterface
        fun liveStream(ch: String) {
            io.execute { reply("liveStream", try { stream(ch).toString() } catch (e: Exception) { "{}" }) }
        }

        /** answers through liveGuide({svt1: [{t, n}, ...], ...}) */
        @JavascriptInterface
        fun liveGuide() {
            io.execute { reply("liveGuide", try { guide().toString() } catch (e: Exception) { "{}" }) }
        }

        /** opens a headline's article in the reader; anything but an svt.se link is refused */
        @JavascriptInterface
        fun open(url: String): Boolean {
            if (!allowed(url)) return false
            main.post {
                try { ctx.startActivity(Intent(ctx, Reader::class.java).putExtra("url", url)) } catch (e: Exception) {}
            }
            return true
        }
    }

    /** The article, full screen. Up and down scroll it, Back closes it. Links stay on svt.se. */
    class Reader : Activity() {
        private lateinit var view: WebView

        override fun onCreate(state: Bundle?) {
            super.onCreate(state)
            val url = intent.getStringExtra("url")?.takeIf { allowed(it) } ?: run { finish(); return }
            view = WebView(this)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.setBackgroundColor(0xFF000000.toInt())
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, u: String): Boolean = !allowed(u)
            }
            setContentView(view)
            view.requestFocus()
            view.loadUrl(url)
        }

        override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { view.flingScroll(0, 2600); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { view.flingScroll(0, -2600); return true }
                KeyEvent.KEYCODE_BACK -> { finish(); return true }
            }
            return super.onKeyDown(code, e)
        }

        override fun onDestroy() {
            try { view.destroy() } catch (e: Exception) {}
            super.onDestroy()
        }
    }

}
