package app.couchtv.launcher

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.Locale

/**
 * Jarvis: the home's own voice assistant (a test).
 *
 * Couch TV can be made the TV's assistant app (in place of Google's), so the remote's microphone
 * button wakes Jarvis on the home: `cmd role add-role-holder android.app.role.ASSISTANT
 * app.couchtv.launcher`. Android then asks Service for a session; Session brings the home forward
 * with "jarvis" set, and the page listens. Speech becomes text through Google's own recognizer on the
 * TV (named outright, since being the assistant makes ours the default, and ours is only a stand-in),
 * and the answer is read out with the TV's text to speech, in Swedish.
 *
 * Undo: `cmd role add-role-holder android.app.role.ASSISTANT com.google.android.katniss`.
 */
object Jarvis {
    /** speech to text on this TV (Google TV): Katniss's (the one Google's own assistant listens
     *  with), then Google's on-device one; on an error the next is tried */
    private val RECOGNIZERS = listOf(
        ComponentName("com.google.android.katniss", "com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService"),
        ComponentName("com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService")
    )

    class Service : VoiceInteractionService()

    class SessionService : VoiceInteractionSessionService() {
        override fun onNewSession(args: Bundle?): VoiceInteractionSession = Session(this)
    }

    class Session(ctx: Context) : VoiceInteractionSession(ctx) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            val i = Intent(context, MainActivity::class.java)
                .putExtra("jarvis", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startAssistantActivity(i) } catch (e: Exception) {
                try { context.startActivity(i) } catch (e2: Exception) {}
            }
            hide()
        }
    }

    /** a stand-in: Android wants an assistant to name a recognizer; Page uses Google's */
    class Recognizer : RecognitionService() {
        override fun onStartListening(intent: Intent?, cb: Callback?) { try { cb?.error(SpeechRecognizer.ERROR_CLIENT) } catch (e: Exception) {} }
        override fun onStopListening(cb: Callback?) {}
        override fun onCancel(cb: Callback?) {}
    }

    /** what the page can ask for: listen(), speak(text), stop(); answers through jarvisEvent({...}) */
    class Page(private val act: Activity, private val web: WebView) {
        private val main = Handler(Looper.getMainLooper())
        private var rec: SpeechRecognizer? = null
        private var recAt = -1          // which of RECOGNIZERS rec is (-1: the default)
        private var heardAny = false
        private var tts: TextToSpeech? = null
        private var ttsReady = false
        private var pending: String? = null

        private fun send(o: JSONObject) {
            main.post { try { web.evaluateJavascript("window.jarvisEvent && jarvisEvent(" + o + ")", null) } catch (e: Exception) {} }
        }
        private fun ev(type: String, text: String = "") = send(JSONObject().put("type", type).put("text", text))

        /** the recognizer at RECOGNIZERS[from] or after (one the TV has), else Android's default */
        private fun recognizer(from: Int): SpeechRecognizer? {
            try { rec?.destroy() } catch (e: Exception) {}
            rec = null
            val pm = act.packageManager
            for (k in from until RECOGNIZERS.size) {
                val c = RECOGNIZERS[k]
                val ok = try { pm.getServiceInfo(c, 0); true } catch (e: Exception) { false }
                if (!ok) continue
                val r = try { SpeechRecognizer.createSpeechRecognizer(act, c) } catch (e: Exception) { null } ?: continue
                rec = r; recAt = k
                return r
            }
            if (from > RECOGNIZERS.size) return null
            recAt = RECOGNIZERS.size
            return try { SpeechRecognizer.createSpeechRecognizer(act).also { rec = it } } catch (e: Exception) { null }
        }
        private fun recName(): String = if (recAt in RECOGNIZERS.indices) RECOGNIZERS[recAt].packageName.substringAfterLast('.') else "default"

        @JavascriptInterface
        fun listen() { main.post { start(0) } }

        private fun start(from: Int) {
            run {
                if (Build.VERSION.SDK_INT >= 23 && act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    try { act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7301) } catch (e: Exception) {}
                    ev("error", "permission"); return
                }
                try { tts?.stop() } catch (e: Exception) {}
                val r = recognizer(from) ?: run { ev("error", "no_recognizer"); return }
                heardAny = false
                ev("using", recName())
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(p: Bundle?) { ev("ready") }
                    override fun onBeginningOfSpeech() { heardAny = true; ev("begin") }
                    override fun onRmsChanged(db: Float) { send(JSONObject().put("type", "rms").put("db", db.toDouble())) }
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() { ev("end") }
                    override fun onError(code: Int) {
                        // this one could not hear: try the next recognizer the TV has, once each
                        if (!heardAny && recAt < RECOGNIZERS.size) {
                            ev("retry", recName() + " " + code)
                            val next = recAt + 1
                            main.postDelayed({ start(next) }, 250)
                            return
                        }
                        ev("error", code.toString() + " " + recName())
                    }
                    override fun onResults(b: Bundle?) {
                        ev("final", b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: "")
                    }
                    override fun onPartialResults(b: Bundle?) {
                        val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                        ev("partial", t)
                    }
                    override fun onEvent(t: Int, p: Bundle?) {}
                })
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "sv-SE")
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, act.packageName)
                try { r.startListening(i) } catch (e: Exception) { ev("error", "start") }
            }
        }

        @JavascriptInterface
        fun stop() {
            main.post {
                try { rec?.cancel() } catch (e: Exception) {}
                try { tts?.stop() } catch (e: Exception) {}
            }
        }

        @JavascriptInterface
        fun speak(text: String) {
            val say = text.take(600)
            main.post {
                if (tts == null) {
                    pending = say
                    tts = TextToSpeech(act) { status ->
                        ttsReady = status == TextToSpeech.SUCCESS
                        if (ttsReady) {
                            tts?.let { t ->
                                val sv = Locale("sv", "SE")
                                if (t.isLanguageAvailable(sv) >= TextToSpeech.LANG_AVAILABLE) t.language = sv
                                t.setSpeechRate(1.0f); t.setPitch(0.92f)
                                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                    override fun onStart(id: String?) { ev("speaking") }
                                    override fun onDone(id: String?) { ev("spoken") }
                                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { ev("spoken") }
                                })
                            }
                            pending?.let { p -> pending = null; say(p) }
                        } else ev("spoken")
                    }
                } else if (ttsReady) say(say) else pending = say
            }
        }

        private fun say(text: String) {
            try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis") } catch (e: Exception) { ev("spoken") }
        }
    }
}
