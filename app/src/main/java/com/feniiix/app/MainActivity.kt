package com.feniiix.app

import android.net.Uri

import android.Manifest
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var overlayServiceIntent: Intent
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
        } else {
            showOverlayPermissionNeeded()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Simple binding for the empty activity layout
        setContentView(R.layout.activity_main)

        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        } else {
            startOverlayService()
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun showOverlayPermissionNeeded() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_needed_title))
            .setMessage(getString(R.string.permission_needed_message))
            .setPositiveButton(getString(R.string.ok)) { _, _ -> requestOverlayPermission() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun startOverlayService() {
        overlayServiceIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(overlayServiceIntent)
        } else {
            startService(overlayServiceIntent)
        }
    }

    // -------------------------------------------------------------------------
    // Overlay Service
    // -------------------------------------------------------------------------
    inner class OverlayService : Service() {

        private lateinit var windowManager: WindowManager
        private var bubbleView: ImageView? = null
        private var panelView: LinearLayout? = null
        private var isPanelVisible = false
        private var speechRecognizer: SpeechRecognizer? = null
        private val httpClient = OkHttpClient()
        private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

        // UI components inside panel
        private lateinit var tabLayout: TabLayout
        private lateinit var voiceContainer: LinearLayout
        private lateinit var textContainer: LinearLayout
        private lateinit var tvFrench: TextView
        private lateinit var tvSpanish: TextView
        private lateinit var btnListen: Button
        private lateinit var etInput: EditText
        private lateinit var btnTranslate: Button
        private lateinit var btnCopy: Button
        private lateinit var btnClosePanel: ImageView

        override fun onBind(intent: Intent?): IBinder? = null

        override fun onCreate() {
            super.onCreate()
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            createBubble()
        }

        private fun createBubble() {
            bubbleView = ImageView(this).apply {
                setImageResource(android.R.drawable.ic_dialog_info)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#66000000"))
                }
                setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
                setOnTouchListener(BubbleTouchListener())
                setOnClickListener {
                    if (!isPanelVisible) {
                        showPanel()
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                dpToPx(56),
                dpToPx(56),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dpToPx(20)
                y = dpToPx(100)
            }

            windowManager.addView(bubbleView, params)
        }

        private fun showPanel() {
            // Remove bubble
            bubbleView?.let { windowManager.removeView(it) }

            // Inflate panel layout programmatically
            panelView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#CC000000"))
                val bg = GradientDrawable()
                bg.cornerRadius = dpToPx(12).toFloat()
                bg.setColor(Color.parseColor("#CC000000"))
                background = bg
                layoutParams = ViewGroup.LayoutParams(dpToPx(320), ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            }

            // Close (X) button
            btnClosePanel = ImageView(this).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setColorFilter(Color.WHITE)
                setOnClickListener { hidePanel() }
            }
            val closeParams = LinearLayout.LayoutParams(
                dpToPx(24),
                dpToPx(24)
            ).apply {
                gravity = Gravity.END
            }
            panelView?.addView(btnClosePanel, closeParams)

            // TabLayout
            tabLayout = TabLayout(this).apply {
                setSelectedTabIndicatorColor(Color.WHITE)
                setTabTextColors(Color.LTGRAY, Color.WHITE)
                addTab(newTab().setText("\uD83C\uDF33 Voz"))
                addTab(newTab().setText("\uD83D\uDCDD Texto"))
            }
            panelView?.addView(tabLayout)

            // Voice container
            voiceContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.VISIBLE
                setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            }

            btnListen = Button(this).apply {
                text = getString(R.string.listen)
            }
            voiceContainer.addView(btnListen)

            tvFrench = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, dpToPx(8), 0, 0)
            }
            voiceContainer.addView(tvFrench)

            tvSpanish = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, dpToPx(4), 0, 0)
            }
            voiceContainer.addView(tvSpanish)

            // Text container
            textContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            }

            etInput = EditText(this).apply {
                hint = getString(R.string.enter_french_text)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.LTGRAY)
                setBackgroundColor(Color.parseColor("#66000000"))
                setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            }
            textContainer.addView(etInput)

            btnTranslate = Button(this).apply {
                text = getString(R.string.translate)
            }
            textContainer.addView(btnTranslate)

            btnCopy = Button(this).apply {
                text = getString(R.string.copy_result)
            }
            textContainer.addView(btnCopy)

            // Add containers to panel
            panelView?.addView(voiceContainer)
            panelView?.addView(textContainer)

            // LayoutParams for panel
            val params = WindowManager.LayoutParams(
                dpToPx(320),
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dpToPx(20)
                y = dpToPx(100)
            }

            windowManager.addView(panelView, params)
            isPanelVisible = true

            // Listeners
            tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    when (tab?.position) {
                        0 -> {
                            voiceContainer.visibility = View.VISIBLE
                            textContainer.visibility = View.GONE
                        }
                        1 -> {
                            voiceContainer.visibility = View.GONE
                            textContainer.visibility = View.VISIBLE
                        }
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })

            btnListen.setOnClickListener { startListening() }
            btnTranslate.setOnClickListener { translateTextInput() }
            btnCopy.setOnClickListener { copyResultToClipboard() }
        }

        private fun hidePanel() {
            panelView?.let { windowManager.removeView(it) }
            isPanelVisible = false
            createBubble()
        }

        // -----------------------------------------------------------------
        // Voice handling
        // -----------------------------------------------------------------
        private fun startListening() {
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    1001
                )
                return
            }

            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        tvFrench.text = getString(R.string.speech_error)
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenText = matches?.firstOrNull() ?: ""
                        tvFrench.text = spokenText
                        translateAndShow(spokenText)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        }

        // -----------------------------------------------------------------
        // Text handling
        // -----------------------------------------------------------------
        private fun translateTextInput() {
            val input = etInput.text.toString()
            if (input.isBlank()) {
                etInput.error = getString(R.string.empty_input)
                return
            }
            translateAndShow(input)
        }

        private fun copyResultToClipboard() {
            val result = tvSpanish.text.toString()
            if (result.isBlank()) return
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("translation", result)
            clipboard.setPrimaryClip(clip)
        }

        // -----------------------------------------------------------------
        // Translation
        // -----------------------------------------------------------------
        private fun translateAndShow(text: String) {
            tvSpanish.text = getString(R.string.translating)
            serviceScope.launch {
                val translation = withContext(Dispatchers.IO) { fetchTranslation(text) }
                tvSpanish.text = translation ?: getString(R.string.translation_failed)
            }
        }

        private fun fetchTranslation(text: String): String? {
            val url = "https://api.mymemory.translated.net/get?q=${java.net.URLEncoder.encode(text, "UTF-8")}&langpair=fr|es"
            val request = Request.Builder().url(url).build()
            return try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return null
                    val body = response.body?.string() ?: return null
                    val json = JSONObject(body)
                    val responseData = json.getJSONObject("responseData")
                    responseData.getString("translatedText")
                }
            } catch (e: IOException) {
                null
            }
        }

        // -----------------------------------------------------------------
        // Helpers
        // -----------------------------------------------------------------
        private fun dpToPx(dp: Int): Int {
            val metrics: DisplayMetrics = resources.displayMetrics
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), metrics).toInt()
        }

        override fun onDestroy() {
            super.onDestroy()
            bubbleView?.let { windowManager.removeView(it) }
            panelView?.let { windowManager.removeView(it) }
            speechRecognizer?.destroy()
        }

        // -----------------------------------------------------------------
        // Touch listener for dragging bubble
        // -----------------------------------------------------------------
        inner class BubbleTouchListener : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                val params = v?.layoutParams as WindowManager.LayoutParams
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(v, params)
                        return true
                    }
                }
                return false
            }
        }
    }
}