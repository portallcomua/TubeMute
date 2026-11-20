package com.custom.reklamoid

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.net.Uri
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var rootLayout: FrameLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalSystemUiVisibility = 0

    private val prefsName = "TubeMutePrefs"
    private val apiBase = "https://uaserver.pp.ua/wp-json/tubemute/v1"
    private val privatUrl = "https://www.privat24.ua/send/k8l6z"
    private val paypalUrl = "https://www.paypal.com/paypalme/portallmail"
    private val youtubeUrl = "https://www.youtube.com"

    private val email: String
        get() = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString("user_email", "") ?: ""

    private val deviceId: String
        get() = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val savedEmail = email
        if (savedEmail.isBlank()) showRegistrationDialog() else verifyAccess(false)
    }

    private fun showRegistrationDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            hint = getString(R.string.reg_hint)
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reg_title)
            .setMessage(R.string.reg_message)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_continue, null)
            .setNegativeButton(R.string.btn_exit) { _, _ -> finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim().lowercase(Locale.ROOT)
                if (!Patterns.EMAIL_ADDRESS.matcher(value).matches() || !value.endsWith("@gmail.com")) {
                    input.error = getString(R.string.err_invalid_email)
                    return@setOnClickListener
                }
                getSharedPreferences(prefsName, MODE_PRIVATE).edit()
                    .putString("user_email", value).apply()
                dialog.dismiss()
                verifyAccess(true)
            }
        }
        dialog.show()
    }

    private fun postApi(path: String, payload: JSONObject): JSONObject {
        val connection = (URL("$apiBase/$path").openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.connectTimeout = 12000
        connection.readTimeout = 12000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            if (code !in 200..299) throw Exception(JSONObject(body).optString("message", "HTTP $code"))
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestPayload(): JSONObject = JSONObject()
        .put("email", email)
        .put("device_id", deviceId)

    private fun verifyAccess(first: Boolean) {
        Toast.makeText(this, getString(R.string.checking_access), Toast.LENGTH_SHORT).show()
        thread {
            try {
                val result = postApi("check", requestPayload())
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    when (result.optString("status")) {
                        "licensed", "temporary" -> {
                            if (first) Toast.makeText(this, getString(R.string.access_activated), Toast.LENGTH_LONG).show()
                            initYouTubePlayer()
                        }
                        "trial" -> {
                            val days = result.optInt("days_left", 7)
                            if (first) Toast.makeText(this, getString(R.string.trial_started), Toast.LENGTH_LONG).show()
                            else Toast.makeText(this, getString(R.string.trial_days_left, days), Toast.LENGTH_LONG).show()
                            initYouTubePlayer()
                        }
                        else -> showPaywall()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) showConnectionError(e.message ?: "")
                }
            }
        }
    }

    private fun showConnectionError(detail: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.connection_error_title)
            .setMessage(getString(R.string.connection_error_message, detail))
            .setCancelable(false)
            .setPositiveButton(R.string.btn_retry) { _, _ -> verifyAccess(false) }
            .setNegativeButton(R.string.btn_exit) { _, _ -> finish() }
            .show()
    }

    private fun showPaywall() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 12, 36, 12)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.paywall_message)
            textSize = 18f
        })
        content.addView(TextView(this).apply {
            text = email
            textSize = 20f
            setPadding(0, 18, 0, 8)
            setTextIsSelectable(true)
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.paywall_title)
            .setView(content)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_pay, null)
            .setNeutralButton(R.string.btn_copy_email, null)
            .setNegativeButton(R.string.btn_refresh, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                activateAndPay(privatUrl)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TubeMute Gmail", email))
                Toast.makeText(this, R.string.email_copied, Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
                verifyAccess(false)
            }
        }
        dialog.show()
    }

    private fun activateAndPay(paymentUrl: String) {
        Toast.makeText(this, R.string.activating_access, Toast.LENGTH_SHORT).show()
        thread {
            try {
                val result = postApi("activate", requestPayload())
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (result.optString("status") == "temporary" || result.optString("status") == "licensed") {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(paymentUrl)))
                        } catch (_: Exception) {
                            Toast.makeText(this, R.string.payment_link_error, Toast.LENGTH_LONG).show()
                        }
                        initYouTubePlayer()
                    } else {
                        showPaywall()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) showConnectionError(e.message ?: "")
                }
            }
        }
    }

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
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.loadsImagesAutomatically = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = TubeChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAdSkipper()
            }
        }
        rootLayout.addView(webView)
        setContentView(rootLayout)
        webView.requestFocus()
        webView.loadUrl(youtubeUrl)
    }

    private inner class TubeChromeClient : WebChromeClient() {
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null || callback == null) return
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customViewCallback = callback
            originalSystemUiVisibility = window.decorView.systemUiVisibility
            webView.visibility = View.GONE
            rootLayout.addView(view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        override fun onHideCustomView() { hideCustomView() }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        rootLayout.removeView(view)
        customView = null
        webView.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = originalSystemUiVisibility
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.requestFocus()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun injectAdSkipper() {
        val jsCode = """
            (function() {
                if (window.__tubeMuteStarted) return;
                window.__tubeMuteStarted = true;
                function processPlayer() {
                    const player = document.querySelector('.html5-video-player');
                    if (!player) return;
                    const video = player.querySelector('video');
                    ['.ytp-ad-skip-button','.ytp-ad-skip-button-modern','.ytp-skip-ad-button'].forEach(function(selector) {
                        player.querySelectorAll(selector).forEach(function(btn) {
                            if (btn && btn.offsetParent !== null && !btn.disabled) btn.click();
                        });
                    });
                    const adShowing = player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting');
                    if (adShowing && video) {
                        video.muted = true;
                        if (video.playbackRate !== 16) video.playbackRate = 16;
                        if (Number.isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.5) {
                            try { video.currentTime = video.duration; } catch (e) {}
                        }
                    } else if (video && video.playbackRate === 16) video.playbackRate = 1;
                }
                setInterval(processPlayer, 500);
                new MutationObserver(processPlayer).observe(document.documentElement, {
                    childList: true, subtree: true, attributes: true, attributeFilter: ['class']
                });
            })();
        """.trimIndent()
        webView.evaluateJavascript(jsCode, null)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) { hideCustomView(); return }
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        if (customView != null) hideCustomView()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }
}
