package com.custom.reklamoid

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    companion object {
        private const val API_BASE = "https://uaserver.pp.ua/wp-json/tubemute/v1"
        private const val GITHUB_LATEST_RELEASE_API =
            "https://api.github.com/repos/portallcomua/TubeMute/releases/latest"
        private const val DEFAULT_PAYMENT_URL = ""
        private const val PREFS = "TubeMutePrefs"
        private const val EMAIL_KEY = "user_email"
        private const val DEVICE_UUID_KEY = "device_uuid"
        private const val REQ_CHOOSE_ACCOUNT = 1001

        private val ALLOWED_WEBVIEW_HOSTS = setOf(
            "youtube.com",
            "www.youtube.com",
            "m.youtube.com",
            "youtu.be",
            "accounts.google.com",
            "consent.youtube.com"
        )
    }

    private var webView: WebView? = null
    private var rootContainer: FrameLayout? = null
    private var currentEmail = ""
    private var currentPaymentUrl = DEFAULT_PAYMENT_URL
    private var currentAccessSummary = ""
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var previousSystemUiVisibility = 0
    private var updateCheckedThisSession = false

    @Volatile private var requestInProgress = false
    @Volatile private var updateCheckInProgress = false

    private val deviceId: String
        get() {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(DEVICE_UUID_KEY, null)
            if (!existing.isNullOrBlank() && existing.length >= 8) {
                return existing
            }
            val generated = "tm-${UUID.randomUUID()}"
            prefs.edit().putString(DEVICE_UUID_KEY, generated).apply()
            return generated
        }

    private val appVersionName: String
        get() = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "2.1.0"
        } catch (_: Exception) {
            "2.1.0"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        currentEmail = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(EMAIL_KEY, "") ?: ""
        if (currentEmail.isBlank()) {
            launchAccountPicker()
        } else {
            checkAccess()
        }
    }

    private fun launchAccountPicker() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AccountManager.newChooseAccountIntent(
                    null,
                    null,
                    arrayOf("com.google"),
                    null,
                    null,
                    null,
                    null
                )
            } else {
                @Suppress("DEPRECATION")
                AccountManager.newChooseAccountIntent(
                    null,
                    null,
                    arrayOf("com.google"),
                    false,
                    null,
                    null,
                    null,
                    null
                )
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_CHOOSE_ACCOUNT)
        } catch (_: Exception) {
            showRegistrationDialog()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CHOOSE_ACCOUNT) {
            val pickedEmail = if (resultCode == RESULT_OK) {
                data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                    ?.trim()
                    ?.lowercase(Locale.ROOT)
                    .orEmpty()
            } else {
                ""
            }
            if (isValidEmail(pickedEmail)) {
                saveEmailAndCheck(pickedEmail)
            } else {
                showRegistrationDialog()
            }
        }
    }

    private fun isValidEmail(email: String): Boolean {
        return email.matches(Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))
    }

    private fun saveEmailAndCheck(email: String) {
        currentEmail = email
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(EMAIL_KEY, email)
            .apply()
        checkAccess()
    }

    private fun showRegistrationDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.reg_hint)
            if (currentEmail.isNotBlank()) setText(currentEmail)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
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
                val email = input.text.toString().trim().lowercase(Locale.ROOT)
                if (!isValidEmail(email)) {
                    Toast.makeText(this, R.string.err_invalid_email, Toast.LENGTH_LONG).show()
                    showRegistrationDialog()
                } else {
                    saveEmailAndCheck(email)
                }
            }
            .setNeutralButton(R.string.btn_device_account) { _, _ -> launchAccountPicker() }
            .setNegativeButton(R.string.btn_exit) { _, _ ->
                if (webView == null) finish()
            }
            .show()
    }

    private fun postCheck(): JSONObject {
        val connection = URL("$API_BASE/check").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            val payload = JSONObject().apply {
                put("email", currentEmail)
                put("device_id", deviceId)
            }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val message = try {
                    JSONObject(response).optString("message", "HTTP $code")
                } catch (_: Exception) {
                    "HTTP $code"
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
                val result = postCheck()
                val status = result.optString("status", "unknown")
                val payment = result.optString("payment_url", "")
                runOnUiThread {
                    requestInProgress = false
                    if (payment.isNotBlank()) currentPaymentUrl = payment
                    when (status) {
                        "licensed" -> {
                            val until = result.optString("until", "PRO")
                            currentAccessSummary = "PRO ($until)"
                            Toast.makeText(this, R.string.access_activated, Toast.LENGTH_SHORT).show()
                            if (webView == null) initYouTubePlayer()
                            triggerAutoUpdateCheckOnce()
                        }
                        "trial" -> {
                            val days = result.optInt("days_left", 0)
                            currentAccessSummary = getString(R.string.trial_days_left, days)
                            if (result.optBoolean("show_reminder", false)) {
                                showTrialReminder(days)
                            } else {
                                Toast.makeText(this, currentAccessSummary, Toast.LENGTH_LONG).show()
                                if (webView == null) initYouTubePlayer()
                                triggerAutoUpdateCheckOnce()
                            }
                        }
                        "expired" -> {
                            currentAccessSummary = getString(R.string.paywall_title)
                            showPaywallDialog()
                        }
                        "blocked" -> {
                            currentAccessSummary = getString(R.string.blocked_title)
                            showBlockedDialog()
                        }
                        else -> showConnectionError(getString(R.string.unknown_server_response))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    requestInProgress = false
                    showConnectionError(e.message ?: getString(R.string.unknown_error))
                }
            }
        }
    }

    private fun triggerAutoUpdateCheckOnce() {
        if (updateCheckedThisSession) return
        updateCheckedThisSession = true
        checkForUpdates(manual = false)
    }

    private fun checkForUpdates(manual: Boolean) {
        if (updateCheckInProgress) return
        updateCheckInProgress = true
        if (manual) {
            Toast.makeText(this, R.string.checking_updates, Toast.LENGTH_SHORT).show()
        }
        thread {
            try {
                val connection = URL(GITHUB_LATEST_RELEASE_API).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    connection.setRequestProperty("User-Agent", "TubeMute-Android/$appVersionName")
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        throw Exception("HTTP $code")
                    }
                    val bodyText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val json = JSONObject(bodyText)
                    val tag = json.optString("tag_name", "").trim()
                    val remoteVersion = tag.removePrefix("v").removePrefix("V")
                    val releaseUrl = json.optString("html_url", "")
                    var apkUrl = ""
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.optJSONObject(i) ?: continue
                            val name = asset.optString("name", "").lowercase(Locale.ROOT)
                            val downloadUrl = asset.optString("browser_download_url", "")
                            if (name.endsWith(".apk") && downloadUrl.isNotBlank()) {
                                apkUrl = downloadUrl
                                break
                            }
                        }
                    }
                    val targetUrl = if (apkUrl.isNotBlank()) apkUrl else releaseUrl
                    val hasUpdate = remoteVersion.isNotBlank() &&
                        compareVersions(remoteVersion, appVersionName) > 0 &&
                        targetUrl.isNotBlank()
                    runOnUiThread {
                        updateCheckInProgress = false
                        if (hasUpdate) {
                            showUpdateAvailableDialog(remoteVersion, targetUrl)
                        } else if (manual) {
                            Toast.makeText(
                                this,
                                getString(R.string.up_to_date, appVersionName),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    updateCheckInProgress = false
                    if (manual) {
                        Toast.makeText(this, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val partsA = a.split(Regex("[^0-9]+")).filter { it.isNotBlank() }.map { it.toIntOrNull() ?: 0 }
        val partsB = b.split(Regex("[^0-9]+")).filter { it.isNotBlank() }.map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val va = partsA.getOrElse(i) { 0 }
            val vb = partsB.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    private fun showUpdateAvailableDialog(remoteVersion: String, downloadUrl: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_message, remoteVersion, appVersionName))
            .setPositiveButton(R.string.btn_download_update) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.update_open_error, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.btn_later, null)
            .show()
    }

    private fun showSettingsDialog() {
        val items = arrayOf(
            getString(R.string.settings_test_access),
            getString(R.string.settings_check_updates),
            getString(R.string.settings_choose_device_account),
            getString(R.string.settings_enter_email),
            getString(R.string.btn_pay)
        )
        val info = getString(
            R.string.settings_summary,
            appVersionName,
            currentEmail.ifBlank { "-" },
            currentAccessSummary.ifBlank { "-" }
        )
        AlertDialog.Builder(this)
            .setTitle("${getString(R.string.settings_title)} ($info)")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> checkAccess()
                    1 -> checkForUpdates(manual = true)
                    2 -> launchAccountPicker()
                    3 -> showRegistrationDialog()
                    4 -> openPayment()
                }
            }
            .setNegativeButton(R.string.btn_close, null)
            .show()
    }

    private fun showTrialReminder(days: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.trial_reminder_title)
            .setMessage(getString(R.string.trial_reminder_message, days))
            .setCancelable(false)
            .setPositiveButton(R.string.btn_pay) { _, _ -> openPayment() }
            .setNeutralButton(R.string.btn_continue_watching) { _, _ ->
                if (webView == null) initYouTubePlayer()
                triggerAutoUpdateCheckOnce()
            }
            .setNegativeButton(R.string.btn_exit) { _, _ -> finish() }
            .show()
    }

    private fun showConnectionError(details: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.connection_error_title)
            .setMessage(getString(R.string.connection_error_message, details))
            .setCancelable(false)
            .setPositiveButton(R.string.btn_retry) { _, _ -> checkAccess() }
            .setNeutralButton(R.string.settings_title) { _, _ -> showSettingsDialog() }
            .setNegativeButton(R.string.btn_exit) { _, _ -> finish() }
            .show()
    }

    private fun showPaywallDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.paywall_title)
            .setMessage(getString(R.string.paywall_message, currentEmail))
            .setCancelable(false)
            .setPositiveButton(R.string.btn_pay) { _, _ -> openPayment() }
            .setNeutralButton(R.string.settings_title) { _, _ -> showSettingsDialog() }
            .setNegativeButton(R.string.btn_exit) { _, _ -> finish() }
            .show()
    }

    private fun showBlockedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.blocked_title)
            .setMessage(R.string.blocked_message)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_exit) { _, _ -> finish() }
            .setNeutralButton(R.string.settings_title) { _, _ -> showSettingsDialog() }
            .show()
    }

    private fun openPayment() {
        if (currentPaymentUrl.isBlank()) {
            Toast.makeText(this, R.string.payment_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentPaymentUrl)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.payment_link_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun youtubeStartUrl(): String {
        val isAndroidTv = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        return if (isAndroidTv) "https://www.youtube.com/tv" else "https://m.youtube.com"
    }

    private fun handleNavigationUrl(uri: Uri?): Boolean {
        val scheme = uri?.scheme?.lowercase(Locale.ROOT) ?: return true
        val host = uri.host?.lowercase(Locale.ROOT) ?: return true
        if (scheme == "https" && ALLOWED_WEBVIEW_HOSTS.any { host == it || host.endsWith(".$it") }) {
            return false
        }
        if (scheme == "https" || scheme == "http") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Exception) {
            }
        }
        return true
    }

    private fun createSettingsOverlayButton(): TextView {
        val density = resources.displayMetrics.density
        val sizePx = (40 * density).toInt()
        val marginPx = (12 * density).toInt()
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(150, 15, 23, 42))
            setStroke((1 * density).toInt(), Color.argb(90, 255, 255, 255))
        }
        return TextView(this).apply {
            text = "\u2699"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            background = bg
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = getString(R.string.settings_title)
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx, Gravity.TOP or Gravity.END).apply {
                topMargin = marginPx
                rightMargin = marginPx
            }
            setOnClickListener { showSettingsDialog() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initYouTubePlayer() {
        customView = null
        customViewCallback = null
        val player = WebView(this)
        webView = player

        val container = FrameLayout(this)
        rootContainer = container
        container.addView(
            player,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        container.addView(createSettingsOverlayButton())
        setContentView(container)

        player.isFocusable = true
        player.isFocusableInTouchMode = true
        player.requestFocus()

        val settings: WebSettings = player.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        player.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (customView != null) { callback?.onCustomViewHidden(); return }
                previousSystemUiVisibility = window.decorView.systemUiVisibility
                customView = view
                customViewCallback = callback
                rootContainer?.visibility = View.GONE
                setContentView(view)
                window.decorView.systemUiVisibility = immersiveSystemUiFlags()
                view.requestFocus()
            }
            override fun onHideCustomView() { hideCustomView() }
        }
        player.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) request?.url else null
                return handleNavigationUrl(uri)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleNavigationUrl(url?.let { Uri.parse(it) })
            }
        }
        player.loadUrl(youtubeStartUrl())
    }

    private fun immersiveSystemUiFlags(): Int = (
        View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    )

    private fun hideCustomView() {
        if (customView == null) return
        customView = null
        setContentView(rootContainer)
        rootContainer?.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = previousSystemUiVisibility
        val callback = customViewCallback
        customViewCallback = null
        callback?.onCustomViewHidden()
        webView?.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            showSettingsDialog()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && customView != null) {
            hideCustomView()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (customView != null) { hideCustomView(); return }
        val player = webView
        if (player != null && player.canGoBack()) player.goBack() else super.onBackPressed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && customView != null) window.decorView.systemUiVisibility = immersiveSystemUiFlags()
    }

    override fun onDestroy() {
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        customView = null
        webView?.apply { stopLoading(); loadUrl("about:blank"); removeAllViews(); destroy() }
        webView = null
        rootContainer = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
