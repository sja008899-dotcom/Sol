package com.sol.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var db: SolDatabase
    private lateinit var brain: SolEngine
    private lateinit var transcript: TextView
    private lateinit var entry: EditText
    private var tts: TextToSpeech? = null
    private var awaitingCall: String? = null
    private val audioReq = 41
    private val contactsReq = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = SolDatabase(this)
        brain = SolEngine(db)
        tts = TextToSpeech(this, this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 28, 22, 18)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF07152F.toInt())
            layoutDirection = LinearLayout.LayoutDirection.RTL
        }
        page.addView(TextView(this).apply {
            text = "S O L"; textSize = 34f; gravity = Gravity.CENTER
            setTextColor(0xFF65E4FF.toInt())
        }, LinearLayout.LayoutParams(-1, 58))
        page.addView(TextView(this).apply {
            text = "دستیار شخصی فارسی"; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(0xFFB9DFFF.toInt())
        }, LinearLayout.LayoutParams(-1, 35))
        val scroll = ScrollView(this)
        transcript = TextView(this).apply {
            textSize = 16f; setTextColor(0xFFEAF5FF.toInt())
            setPadding(15, 18, 15, 18); gravity = Gravity.RIGHT
            textDirection = android.view.View.TEXT_DIRECTION_RTL
            setBackgroundColor(0xFF10264A.toInt())
        }
        scroll.addView(transcript)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        entry = EditText(this).apply {
            hint = "فرمانت را بنویس..."; textSize = 16f
            textDirection = android.view.View.TEXT_DIRECTION_RTL
        }
        page.addView(entry, LinearLayout.LayoutParams(-1, 58))
        page.addView(Button(this).apply {
            text = "ارسال فرمان"
            setOnClickListener { process(entry.text.toString()); entry.setText("") }
        }, LinearLayout.LayoutParams(-1, 52))
        page.addView(Button(this).apply {
            text = "🎙 صحبت با Sol"; setOnClickListener { listen() }
        }, LinearLayout.LayoutParams(-1, 52))
        page.addView(Button(this).apply {
            text = "نمایش حافظه"
            setOnClickListener {
                val items = db.allMemories()
                show("حافظه", if (items.isEmpty()) "حافظه خالی است." else items.joinToString("\n") { it.second })
            }
        }, LinearLayout.LayoutParams(-1, 48))
        setContentView(page)
        show("Sol", "سلام. من Sol هستم. می‌توانی تایپ کنی یا دکمهٔ میکروفون را بزنی.")
    }

    private fun process(text: String) {
        if (text.isBlank()) return
        show("شما", text)
        val reply = brain.execute(text)
        show("Sol", reply.text)
        tts?.speak(reply.text, TextToSpeech.QUEUE_FLUSH, null, "sol_reply")
        db.history(text, reply.text)
        if (reply.callName != null) confirmContact(reply.callName)
    }

    private fun confirmContact(name: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            awaitingCall = name
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), contactsReq)
            return
        }
        dialContact(name)
    }

    private fun dialContact(name: String) {
        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
        var foundName: String? = null
        var foundNumber: String? = null
        contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                if (n.contains(name, true)) { foundName = n; foundNumber = c.getString(1); break }
            }
        }
        if (foundNumber == null) { show("Sol", "مخاطبی با نام $name پیدا نکردم."); return }
        android.app.AlertDialog.Builder(this)
            .setTitle("تأیید تماس")
            .setMessage("شمارهٔ $foundName (${foundNumber}) در شماره‌گیر باز شود؟")
            .setNegativeButton("لغو", null)
            .setPositiveButton("تأیید") { _, _ ->
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(foundNumber)}")))
            }.show()
    }

    private fun listen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), audioReq); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { show("Sol", "سرویس تشخیص گفتار در دسترس نیست."); return }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { show("Sol", "گوش می‌دهم...") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { show("Sol", "تشخیص گفتار ناموفق بود؛ کد خطا: $error"); recognizer.destroy() }
            override fun onResults(results: Bundle?) {
                val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                recognizer.destroy()
                if (!words.isNullOrBlank()) runOnUiThread { process(words) }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioReq && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
        if (requestCode == contactsReq && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            awaitingCall?.let { dialContact(it) }; awaitingCall = null
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val language = tts?.setLanguage(Locale("fa", "IR"))
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED)
                show("Sol", "صدای فارسی در موتور گفتار گوشی موجود نیست؛ پاسخ متنی فعال است.")
        }
    }

    private fun show(who: String, message: String) {
        runOnUiThread {
            val old = transcript.text.toString()
            transcript.text = (if (old.isBlank()) "" else "$old\n\n") + "$who:\n$message"
        }
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown(); db.close()
        super.onDestroy()
    }
}
