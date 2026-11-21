package com.custom.reklamoid

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        private const val API_BASE = "https://uaserver.pp.ua/wp-json/tubemute/v1"
        private const val PAYMENT_URL = "https://www.privat24.ua/send/k8l6z"
        private const val PREFS = "TubeMutePrefs"
        private const val EMAIL_KEY = "user_email"
    }

    private var webView: WebView? = null
    private var currentEmail = ""
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var previousSystemUiVisibility = 0

    @Volatile
    private var requestInProgress = false

    private val deviceId: String
        get() = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown-android-device"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        currentEmail = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(EMAIL_KEY, "") ?: ""

        if (currentEmail.isBlank()) {
            showRegistrationDialog()
        } else {
            checkAccess()
        }
    }

    private fun showRegistrationDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.reg_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.reg_title)
            .setMessage(R.string.reg_message)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_continue) { _, _ ->
                val email = input.text.toString()
                    .trim()
                    .toLowerCase(Locale.ROOT)

                if (!email.matches(Regex("^[A-Za-z0-9._%+-]+@gmail\\.com$"))) {
                    Toast.makeText(
                        this,
                        R.string.err_invalid_email,
                        Toast.LENGTH_LONG
                    ).show()
                    showRegistrationDialog()
                } else {
                    currentEmail = email
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString(EMAIL_KEY, email)
                        .apply()
                    checkAccess()
                }
            }
            .setNegativeButton(R.string.btn_exit) { _, _ -> finish() }
            .show()
    }

    private fun postRequest(endpoint: String): JSONObject {
        val connection = URL("$API_BASE/$endpoint")
            .openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )
            connection.setRequestProperty("Accept", "application/json")

            val payload = JSONObject().apply {
                put("email", currentEmail)
                put("device_id", deviceId)
            }

            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
                output.flush()
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText()
            } ?: ""

            if (responseCode !in 200..299) {
                val message = try {
                    JSONObject(response).optString("message", "HTTP $responseCode")
                } catch (_: Exception) {
                    "HTTP $responseCode"
                }
                throw Exception(message)
            }

            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun checkAccess() {
        if (requestInProgress) return
        requestInProgress = true

        Toast.makeText(this, R.string.checking_access, Toast.LENGTH_SHORT).show()

        thread {
            try {
                val result = postRequest("check")
                val status = result.optString("status", "unknown")

                runOnUiThread {
                    requestInProgress = false
                    when (status) {
                        "licensed", "temporary" -> {
                            Toast.makeText(
                                this,
                                R.string.access_activated,
                                Toast.LENGTH_SHORT
                            ).show()
                            initYouTubePlayer()
                        }
                        "trial" -> {
                            val days = result.optInt("days_left", 0)
                            Toast.makeText(
                                this,
                                getString(R.string.trial_days_left, days),
                                Toast.LENGTH_LONG
                            ).show()
                            initYouTubePlayer()
                        }
                        "expired" -> showPaywallDialog()
                        else -> showConnectionError(
                            getString(R.string.unknown_server_response)
                        )
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    requestInProgress = false
                    showConnectionError(
                        e.message ?: getString(R.string.unknown_error)
                    )
                }
            }
        }
    }

    private fun showConnectionError(details: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.connection_error_title)
            .setMessage(
                getString(R.string.connection_error_message, details)
            )
            .setCancelable(false)
            .setPositiveButton(R.string.btn_retry) { _, _ -> checkAccess() }
            .setNegativeButton(R.string.btn_exit) { _, _ -> finish() }
            .show()
    }

    private fun showPaywallDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.paywall_title)
            .setMessage(getString(R.string.paywall_message, currentEmail))
            .setCancelable(false)
            .setPositiveButton(R.string.btn_pay) { _, _ ->
                activateAndOpenPayment()
            }
            .setNeutralButton(R.string.btn_refresh) { _, _ -> checkAccess() }
            .setNegativeButton(R.string.btn_exit) { _, _ -> finish() }
            .show()
    }

    private fun activateAndOpenPayment() {
        if (requestInProgress) return
        requestInProgress = true

        Toast.makeText(
            this,
            R.string.activating_access,
            Toast.LENGTH_LONG
        ).show()

        thread {
            try {
                val result = postRequest("activate")
                val status = result.optString("status", "")

                runOnUiThread {
                    requestInProgress = false
                    if (status == "temporary" || status == "licensed") {
                        try {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(PAYMENT_URL))
                            )
                        } catch (_: Exception) {
                            Toast.makeText(
                                this,
                                R.string.payment_link_error,
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        Toast.makeText(
                            this,
                            R.string.payment_reminder,
                            Toast.LENGTH_LONG
                        ).show()
                        initYouTubePlayer()
                    } else {
                        showConnectionError(
                            getString(R.string.activation_failed)
                        )
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    requestInProgress = false
                    showConnectionError(
                        e.message ?: getString(R.string.unknown_error)
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initYouTubePlayer() {
        customView = null
        customViewCallback = null

        val player = WebView(this)
        webView = player
        setContentView(player)

        player.isFocusable = true
        player.isFocusableInTouchMode = true
        player.requestFocus()

        val settings: WebSettings = player.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        player.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(
                view: View?,
                callback: CustomViewCallback?
            ) {
                if (view == null) return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }

                previousSystemUiVisibility =
                    window.decorView.systemUiVisibility
                customView = view
                customViewCallback = callback
                player.visibility = View.GONE
                setContentView(view)
                window.decorView.systemUiVisibility = immersiveSystemUiFlags()
                view.requestFocus()
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }

        player.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAdSkipper()
            }
        }

        player.loadUrl("https://www.youtube.com/tv")
    }

    private fun immersiveSystemUiFlags(): Int {
        return (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun hideCustomView() {
        if (customView == null) return

        customView = null
        setContentView(webView)
        webView?.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = previousSystemUiVisibility

        val callback = customViewCallback
        customViewCallback = null
        callback?.onCustomViewHidden()
        webView?.requestFocus()
    }

    private fun injectAdSkipper() {
        val player = webView ?: return
        val script = """
            (function() {
                if (window.tubeMuteAdSkipper) return;
                window.tubeMuteAdSkipper = true;

                setInterval(function() {
                    try {
                        var buttons = document.querySelectorAll(
                            '.ytp-ad-skip-button,' +
                            '.ytp-ad-skip-button-modern,' +
                            '.ytp-skip-ad-button'
                        );

                        buttons.forEach(function(button) {
                            if (button && button.offsetParent !== null) {
                                button.click();
                            }
                        });

                        var ad = document.querySelector(
                            '.ad-showing, .ad-interrupting'
                        );
                        var video = document.querySelector('video');

                        if (ad && video) {
                            // Skip the advertisement immediately by seeking to its end.
                            var duration = video.duration;
                            if (isFinite(duration) && duration > 0) {
                                video.currentTime = duration;
                            }
                        }
                    } catch (e) {}
                }, 500);
            })();
        """.trimIndent()

        player.evaluateJavascript(script, null)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && customView != null) {
            hideCustomView()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (customView != null) {
            hideCustomView()
            return
        }

        val player = webView
        if (player != null && player.canGoBack()) {
            player.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && customView != null) {
            window.decorView.systemUiVisibility = immersiveSystemUiFlags()
        }
    }

    override fun onDestroy() {
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        customView = null

        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }
        webView = null

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
