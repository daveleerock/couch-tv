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
        c.setRequestProperty("User-Agent", "couch-tv")
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
