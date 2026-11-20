package com.custom.reklamoid

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // 1. ВСТАВТЕ СЮДИ RAW-ПОСИЛАННЯ НА ВАШ ФАЙЛ users.txt З РЕПОЗИТОРІЮ:
    private val USERS_DB_URL = "https://githubusercontent.com"

    // 2. ПОСИЛАННЯ ДЛЯ АВТО-ВІДПРАВКИ ЛИСТІВ (підключите пізніше через Google Script або Formspree):
    private val EMAIL_WEBHOOK_URL = ""

    private val TRIAL_DAYS = 7L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("ReklamoidPrefs", Context.MODE_PRIVATE)
        val savedEmail = prefs.getString("user_email", null)

        if (savedEmail != null) {
            verifyAccess(savedEmail, isFirstRegistration = false)
        } else {
            showRegistrationDialog()
        }
    }

    private fun showRegistrationDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        input.hint = getString(R.string.reg_hint)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reg_title))
            .setMessage(getString(R.string.reg_message))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.btn_continue)) { _, _ ->
                val email = input.text.toString().trim().lowercase(Locale.ROOT)
                if (email.contains("@") && email.contains(".")) {
                    val prefs = getSharedPreferences("ReklamoidPrefs", Context.MODE_PRIVATE)
                    // Фіксуємо час першої реєстрації, якщо ще не зафіксовано
                    if (!prefs.contains("first_install_time")) {
                        prefs.edit().putLong("first_install_time", System.currentTimeMillis()).apply()
                    }
                    prefs.edit().putString("user_email", email).apply()

                    // Надсилаємо сповіщення на вебхук про нового користувача
                    sendEmailWebhook(email, "NEW_TRIAL_USER")

                    verifyAccess(email, isFirstRegistration = true)
                } else {
                    Toast.makeText(this, getString(R.string.err_invalid_email), Toast.LENGTH_SHORT).show()
                    showRegistrationDialog()
                }
            }
            .setNegativeButton(getString(R.string.btn_exit)) { _, _ -> finish() }
            .show()
    }

    private fun verifyAccess(email: String, isFirstRegistration: Boolean) {
        Toast.makeText(this, getString(R.string.checking_access), Toast.LENGTH_SHORT).show()

        thread {
            var isPaidOrVip = false
            var expireDateStr = ""

            // 1. Перевіряємо білий список (users.txt)
            try {
                val text = URL("$USERS_DB_URL?t=${System.currentTimeMillis()}").readText()
                for (line in text.lines()) {
                    val parts = line.trim().split("|")
                    if (parts.size >= 2) {
                        val dbEmail = parts.trim().lowercase(Locale.ROOT)
                        val validUntil = parts.trim().uppercase(Locale.ROOT)

                        if (dbEmail == email) {
                            if (validUntil == "LIFETIME") {
                                isPaidOrVip = true
                                expireDateStr = "VIP (Lifetime)"
                            } else {
                                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                                val expDate = sdf.parse(validUntil)
                                if (expDate != null && !Date().after(expDate)) {
                                    isPaidOrVip = true
                                    expireDateStr = validUntil
                                }
                            }
                            break
                        }
                    }
                }
            } catch (_: Exception) {
                // Якщо файл недоступний (немає мережі), перевіримо тріал нижче
            }

            runOnUiThread {
                if (isPaidOrVip) {
                    if (isFirstRegistration) {
                        Toast.makeText(this, getString(R.string.vip_active, expireDateStr), Toast.LENGTH_LONG).show()
                    }
                    initYouTubePlayer()
                } else {
                    // 2. Перевіряємо 7-денний безкоштовний період
                    val prefs = getSharedPreferences("ReklamoidPrefs", Context.MODE_PRIVATE)
                    val installTime = prefs.getLong("first_install_time", System.currentTimeMillis())
                    val daysPassed = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - installTime)
                    val daysLeft = TRIAL_DAYS - daysPassed

                    if (daysLeft > 0) {
                        val msg = if (isFirstRegistration) {
                            getString(R.string.trial_started)
                        } else {
                            getString(R.string.trial_days_left, daysLeft)
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        initYouTubePlayer()
                    } else {
                        // Тріал закінчився — відправляємо сигнал на лист про оплату 299 грн і блокуємо екран
                        sendEmailWebhook(email, "TRIAL_EXPIRED_SEND_INVOICE_299UAH")
                        showPaywallDialog(email)
                    }
                }
            }
        }
    }

    private fun sendEmailWebhook(email: String, eventType: String) {
        if (EMAIL_WEBHOOK_URL.isEmpty()) return
        thread {
            try {
                val tvId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
                val lang = Locale.getDefault().language
                val url = URL(EMAIL_WEBHOOK_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.doOutput = true
                val jsonBody = """{"email":"$email","event":"$eventType","tv_id":"$tvId","lang":"$lang"}"""
                conn.outputStream.use { it.write(jsonBody.toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun showPaywallDialog(email: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.paywall_title))
            .setMessage(getString(R.string.paywall_message, email))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.btn_change_email)) { _, _ ->
                showRegistrationDialog()
            }
            .setNegativeButton(getString(R.string.btn_exit)) { _, _ -> finish() }
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initYouTubePlayer() {
        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.userAgentString = "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/4.0 Chrome/76.0.3809.146 TV Safari/537.36"

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAdSkipper()
            }
        }

        webView.loadUrl("https://youtube.com")
    }

    private fun injectAdSkipper() {
        val jsCode = """
            (function() {
                if (window.adSkipperLoaded) return;
                window.adSkipperLoaded = true;
                
                setInterval(function() {
                    const skipBtns = document.querySelectorAll('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, [class*="skip"]');
                    skipBtns.forEach(btn => {
                        if (btn && btn.offsetParent !== null) btn.click();
                    });

                    const adShowing = document.querySelector('.ad-showing, .ad-interrupting');
                    const video = document.querySelector('video');
                    if (adShowing && video) {
                        video.muted = true;
                        video.playbackRate = 16.0;
                        if (isFinite(video.duration) && video.duration > 0) {
                            video.currentTime = video.duration;
                        }
                    }
                }, 300);
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsCode, null)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
