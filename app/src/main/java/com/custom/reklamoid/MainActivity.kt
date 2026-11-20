package com.custom.reklamoid

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var rootLayout: FrameLayout

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalSystemUiVisibility = 0

    private val prefsName = "TubeMutePrefs"

    // RAW-посилання на список ліцензій.
    private val usersDbUrl =
        "https://raw.githubusercontent.com/portallcomua/TubeMute/main/users.txt"

    private val trialDays = 7L

    private val youtubeUrl = "https://www.youtube.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val savedEmail = prefs.getString("user_email", null)

        if (!savedEmail.isNullOrBlank()) {
            verifyAccess(savedEmail, false)
        } else {
            showRegistrationDialog()
        }
    }

    // --------------------------------------------------
    // РЕЄСТРАЦІЯ
    // --------------------------------------------------

    private fun showRegistrationDialog() {

        val input = EditText(this)

        input.inputType =
            InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS

        input.hint = getString(R.string.reg_hint)
        input.setSingleLine(true)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reg_title))
            .setMessage(getString(R.string.reg_message))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.btn_continue), null)
            .setNegativeButton(getString(R.string.btn_exit)) { _, _ ->
                finish()
            }
            .create()
            .also { dialog ->

                dialog.setOnShowListener {

                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener {

                            val email = input.text.toString()
                                .trim()
                                .lowercase(Locale.ROOT)

                            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches() ||
                                !email.endsWith("@gmail.com")
                            ) {
                                Toast.makeText(
                                    this,
                                    getString(R.string.err_invalid_email),
                                    Toast.LENGTH_LONG
                                ).show()
                                return@setOnClickListener
                            }

                            val prefs = getSharedPreferences(
                                prefsName,
                                Context.MODE_PRIVATE
                            )

                            if (!prefs.contains("first_install_time")) {
                                prefs.edit()
                                    .putLong(
                                        "first_install_time",
                                        System.currentTimeMillis()
                                    )
                                    .apply()
                            }

                            prefs.edit()
                                .putString("user_email", email)
                                .apply()

                            dialog.dismiss()

                            verifyAccess(email, true)
                        }
                }

                dialog.show()
            }
    }

    // --------------------------------------------------
    // ЗАВАНТАЖЕННЯ БАЗИ ЛІЦЕНЗІЙ
    // --------------------------------------------------

    private fun fetchUsersDatabase(): String {

        val connection =
            URL(usersDbUrl).openConnection() as HttpURLConnection

        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.requestMethod = "GET"
        connection.useCaches = false

        try {

            val response = connection.responseCode

            if (response !in 200..299) {
                throw Exception("HTTP $response")
            }

            return connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

        } finally {
            connection.disconnect()
        }
    }

    // --------------------------------------------------
    // ПЕРЕВІРКА ЛІЦЕНЗІЇ
    // --------------------------------------------------

    private fun verifyAccess(
        email: String,
        isFirstRegistration: Boolean
    ) {

        Toast.makeText(
            this,
            getString(R.string.checking_access),
            Toast.LENGTH_SHORT
        ).show()

        thread {

            var paid = false
            var expiration = ""

            try {

                val database = fetchUsersDatabase()

                for (line in database.lines()) {

                    val parts = line.trim().split("|")

                    if (parts.size < 2) continue

                    val dbEmail = parts[0]
                        .trim()
                        .lowercase(Locale.ROOT)

                    val validUntil = parts[1]
                        .trim()
                        .uppercase(Locale.ROOT)

                    if (dbEmail != email) continue

                    if (validUntil == "LIFETIME") {

                        paid = true
                        expiration = "LIFETIME"

                    } else {

                        val sdf = SimpleDateFormat(
                            "yyyy-MM-dd",
                            Locale.ROOT
                        )

                        sdf.isLenient = false

                        val parsedDate = sdf.parse(validUntil)

                        if (parsedDate != null) {

                            // Ліцензія діє до кінця зазначеного дня.
                            val expirationExclusive =
                                Calendar.getInstance().apply {
                                    time = parsedDate
                                    add(Calendar.DAY_OF_YEAR, 1)
                                }.time

                            if (Date().before(expirationExclusive)) {
                                paid = true
                                expiration = validUntil
                            }
                        }
                    }

                    break
                }

            } catch (_: Exception) {
                // Якщо сервер недоступний, перевіряємо локальний тестовий період.
            }

            runOnUiThread {

                if (isFinishing || isDestroyed) return@runOnUiThread

                if (paid) {

                    if (isFirstRegistration) {

                        Toast.makeText(
                            this,
                            getString(
                                R.string.vip_active,
                                expiration
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    initYouTubePlayer()
                    return@runOnUiThread
                }

                checkTrialPeriod(email, isFirstRegistration)
            }
        }
    }

    // --------------------------------------------------
    // БЕЗКОШТОВНИЙ ПЕРІОД
    // --------------------------------------------------

    private fun checkTrialPeriod(
        email: String,
        isFirstRegistration: Boolean
    ) {

        val prefs = getSharedPreferences(
            prefsName,
            Context.MODE_PRIVATE
        )

        val installTime = prefs.getLong(
            "first_install_time",
            System.currentTimeMillis()
        )

        val elapsed = System.currentTimeMillis() - installTime

        val trialMillis = TimeUnit.DAYS.toMillis(trialDays)

        val daysLeft = (
            (trialMillis - elapsed + TimeUnit.DAYS.toMillis(1) - 1) /
                TimeUnit.DAYS.toMillis(1)
            ).coerceAtLeast(0)

        if (elapsed < trialMillis) {

            val message = if (isFirstRegistration) {
                getString(R.string.trial_started)
            } else {
                getString(R.string.trial_days_left, daysLeft)
            }

            Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
            ).show()

            initYouTubePlayer()

        } else {

            showPaywallDialog(email)
        }
    }

    // --------------------------------------------------
    // ОПЛАТА
    // --------------------------------------------------

    private fun showPaywallDialog(email: String) {

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.paywall_title))
            .setMessage(
                getString(
                    R.string.paywall_message,
                    email
                )
            )
            .setCancelable(false)
            .setPositiveButton(
                getString(R.string.btn_change_email)
            ) { _, _ ->

                showRegistrationDialog()
            }
            .setNegativeButton(
                getString(R.string.btn_exit)
            ) { _, _ ->

                finish()
            }
            .show()
    }

    // --------------------------------------------------
    // WEBVIEW
    // --------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun initYouTubePlayer() {

        if (::webView.isInitialized) return

        rootLayout = FrameLayout(this)

        webView = WebView(this)

        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        val settings: WebSettings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        settings.mediaPlaybackRequiresUserGesture = false

        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)

        settings.mixedContentMode =
            WebSettings.MIXED_CONTENT_NEVER_ALLOW

        settings.loadsImagesAutomatically = true

        CookieManager.getInstance().setAcceptCookie(true)

        CookieManager.getInstance()
            .setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = TubeChromeClient()

        webView.webViewClient = object : WebViewClient() {

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {

                super.onPageFinished(view, url)

                injectAdSkipper()
            }
        }

        rootLayout.addView(webView)

        setContentView(rootLayout)

        webView.requestFocus()

        webView.loadUrl(youtubeUrl)
    }

    // --------------------------------------------------
    // ПОВНОЕКРАННЕ ВІДЕО
    // --------------------------------------------------

    private inner class TubeChromeClient : WebChromeClient() {

        override fun onShowCustomView(
            view: View?,
            callback: CustomViewCallback?
        ) {

            if (view == null || callback == null) return

            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }

            customView = view
            customViewCallback = callback

            originalSystemUiVisibility =
                window.decorView.systemUiVisibility

            webView.visibility = View.GONE

            rootLayout.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            window.decorView.systemUiVisibility =
                (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }

        override fun onHideCustomView() {

            hideCustomView()
        }
    }

    private fun hideCustomView() {

        val currentView = customView ?: return

        rootLayout.removeView(currentView)

        customView = null

        webView.visibility = View.VISIBLE

        window.decorView.systemUiVisibility =
            originalSystemUiVisibility

        customViewCallback?.onCustomViewHidden()

        customViewCallback = null

        webView.requestFocus()
    }

    // --------------------------------------------------
    // ОБРОБКА РЕКЛАМНИХ ЕЛЕМЕНТІВ
    // --------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun injectAdSkipper() {

        val jsCode = """
            (function() {

                if (window.__tubeMuteStarted) return;

                window.__tubeMuteStarted = true;

                function processPlayer() {

                    const player =
                        document.querySelector('.html5-video-player');

                    if (!player) return;

                    const video = player.querySelector('video');

                    const skipSelectors = [
                        '.ytp-ad-skip-button',
                        '.ytp-ad-skip-button-modern',
                        '.ytp-skip-ad-button'
                    ];

                    skipSelectors.forEach(function(selector) {

                        player.querySelectorAll(selector).forEach(function(btn) {

                            if (
                                btn &&
                                btn.offsetParent !== null &&
                                !btn.disabled
                            ) {
                                btn.click();
                            }

                        });

                    });

                    const adShowing =
                        player.classList.contains('ad-showing') ||
                        player.classList.contains('ad-interrupting');

                    if (adShowing && video) {

                        video.muted = true;

                        if (video.playbackRate !== 16) {
                            video.playbackRate = 16;
                        }

                        if (
                            Number.isFinite(video.duration) &&
                            video.duration > 0 &&
                            video.currentTime < video.duration - 0.5
                        ) {
                            try {
                                video.currentTime = video.duration;
                            } catch (e) {}
                        }

                    } else if (video && video.playbackRate === 16) {

                        video.playbackRate = 1;
                    }
                }

                setInterval(processPlayer, 500);

                const observer = new MutationObserver(processPlayer);

                observer.observe(document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['class']
                });

            })();
        """.trimIndent()

        webView.evaluateJavascript(jsCode, null)
    }

    // --------------------------------------------------
    // ПУЛЬТ ТЕЛЕВІЗОРА
    // --------------------------------------------------

    @Suppress("DEPRECATION")
    override fun onBackPressed() {

        if (customView != null) {

            hideCustomView()
            return
        }

        if (::webView.isInitialized && webView.canGoBack()) {

            webView.goBack()

        } else {

            super.onBackPressed()
        }
    }

    // --------------------------------------------------
    // ЗАВЕРШЕННЯ РОБОТИ
    // --------------------------------------------------

    override fun onDestroy() {

        if (customView != null) {
            hideCustomView()
        }

        if (::webView.isInitialized) {

            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }

        super.onDestroy()
    }
}
