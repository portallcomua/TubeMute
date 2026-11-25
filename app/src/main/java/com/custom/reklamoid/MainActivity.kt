package com.custom.reklamoid

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
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
        private const val GUEST_DOMAIN = "@device.tubemute.app"

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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var videoPromptRunnable: Runnable? = null
    private var currentPromptedVideoId = ""
    private val promptedVideoIds = mutableSetOf<String>()
    private val downloadingVideoIds = mutableSetOf<String>()
    private var customBrowsingActive = false
    private var currentWebUrl = ""
    private var currentWebUserAgent = ""
    private val promptedExternalMediaUrls = mutableSetOf<String>()
    private val downloadingExternalMediaUrls = mutableSetOf<String>()
    @Volatile private var lastSniffedMp4Url = ""
    @Volatile private var lastSniffedPageUrl = ""
    private var offlineOverlay: FrameLayout? = null
    private var offlineVideoView: VideoView? = null

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
            packageManager.getPackageInfo(packageName, 0).versionName ?: "2.3.3"
        } catch (_: Exception) {
            "2.3.3"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        currentEmail = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(EMAIL_KEY, "") ?: ""
        if (currentEmail.isNotBlank()) {
            checkAccess()
            return
        }
        val detected = findVisibleGoogleEmail()
        if (detected.isNotBlank()) {
            saveEmailAndCheck(detected)
        } else {
            showWelcomeAccountChoice()
        }
    }

    private fun findVisibleGoogleEmail(): String {
        return try {
            AccountManager.get(this).getAccountsByType("com.google")
                .firstOrNull { isValidEmail(it.name.trim()) }
                ?.name?.trim()?.lowercase(Locale.ROOT).orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun activateGuestDeviceAccount() {
        val raw = deviceId.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        val suffix = raw.take(12).ifBlank { "device" }
        saveEmailAndCheck("tv-$suffix$GUEST_DOMAIN")
    }

    private fun displayEmail(): String {
        return if (currentEmail.endsWith(GUEST_DOMAIN)) {
            getString(R.string.guest_account_label)
        } else {
            currentEmail.ifBlank { "-" }
        }
    }

    private fun showWelcomeAccountChoice() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(R.string.welcome_message)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_continue_without_account) { _, _ ->
                activateGuestDeviceAccount()
            }
            .setNeutralButton(R.string.btn_device_account) { _, _ ->
                launchAccountPicker()
            }
            .setNegativeButton(R.string.settings_enter_email) { _, _ ->
                showRegistrationDialog()
            }
            .show()
    }

    private fun launchAccountPicker() {
        val visible = findVisibleGoogleEmail()
        if (visible.isNotBlank() && currentEmail.isBlank()) {
            saveEmailAndCheck(visible)
            return
        }
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
            if (currentEmail.isBlank()) {
                activateGuestDeviceAccount()
            } else {
                showRegistrationDialog()
            }
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
            } else if (currentEmail.isBlank()) {
                showWelcomeAccountChoice()
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
            if (currentEmail.isNotBlank() && !currentEmail.endsWith(GUEST_DOMAIN)) {
                setText(currentEmail)
            }
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
            .setNegativeButton(R.string.btn_continue_without_account) { _, _ ->
                if (currentEmail.isBlank()) {
                    activateGuestDeviceAccount()
                }
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
                            showUpdateAvailableDialog(remoteVersion, apkUrl, releaseUrl)
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

    private fun showUpdateAvailableDialog(
        remoteVersion: String,
        apkUrl: String,
        releaseUrl: String
    ) {
        val targetUrl = apkUrl.ifBlank { releaseUrl }
        AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_message, remoteVersion, appVersionName))
            .setPositiveButton(R.string.btn_download_update) { _, _ ->
                openUrlInBrowser(targetUrl)
            }
            .setNegativeButton(R.string.btn_later, null)
            .show()
    }

    private fun openUrlInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.update_open_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun showSettingsDialog() {
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        if (isSmartphoneDevice()) {
            entries.add(getString(R.string.settings_open_link) to { showOpenLinkDialog() })
            if (customBrowsingActive || (currentWebUrl.isNotBlank() && !isYouTubeHost(currentWebUrl))) {
                entries.add(
                    getString(R.string.settings_back_to_youtube) to {
                        openUrlInWebView(youtubeStartUrl(), customBrowsing = false)
                    }
                )
            }
        }
        entries.add(getString(R.string.settings_test_access) to { checkAccess() })
        entries.add(getString(R.string.settings_check_updates) to { checkForUpdates(manual = true) })
        entries.add(getString(R.string.settings_choose_device_account) to { launchAccountPicker() })
        entries.add(getString(R.string.settings_enter_email) to { showRegistrationDialog() })
        entries.add(getString(R.string.settings_pro_info) to { showProInfoDialog() })

        val labels = entries.map { it.first }.toTypedArray()
        val info = getString(
            R.string.settings_summary,
            appVersionName,
            displayEmail(),
            currentAccessSummary.ifBlank { "-" }
        )
        AlertDialog.Builder(this)
            .setTitle("${getString(R.string.settings_title)} ($info)")
            .setItems(labels) { _, which ->
                entries.getOrNull(which)?.second?.invoke()
            }
            .setNegativeButton(R.string.btn_close, null)
            .show()
    }

    private fun showProInfoDialog() {
        val statusText = currentAccessSummary.ifBlank { "-" }
        val message = getString(R.string.pro_info_message, displayEmail(), statusText)
        AlertDialog.Builder(this)
            .setTitle(R.string.pro_info_title)
            .setMessage(message)
            .setPositiveButton(R.string.btn_go_to_payment) { _, _ -> openPayment() }
            .setNegativeButton(R.string.btn_close, null)
            .show()
    }

    private fun showOpenLinkDialog(initialText: String = "") {
        val input = EditText(this).apply {
            hint = getString(R.string.open_link_hint)
            if (initialText.isNotBlank()) {
                setText(initialText)
            } else if (customBrowsingActive && currentWebUrl.isNotBlank()) {
                setText(currentWebUrl)
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.open_link_title)
            .setMessage(R.string.open_link_message)
            .setView(input)
            .setPositiveButton(R.string.btn_open) { _, _ ->
                val raw = input.text.toString().trim()
                val validUrl = normalizeAndValidateUserUrl(raw)
                if (validUrl == null) {
                    Toast.makeText(this, R.string.err_invalid_url, Toast.LENGTH_LONG).show()
                    showOpenLinkDialog(raw)
                } else {
                    openUrlInWebView(validUrl, customBrowsing = !isYouTubeHost(validUrl))
                }
            }
            .setNeutralButton(R.string.btn_open_youtube) { _, _ ->
                openUrlInWebView(youtubeStartUrl(), customBrowsing = false)
            }
            .setNegativeButton(R.string.btn_close, null)
            .show()
    }

    private fun normalizeAndValidateUserUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> "https://" + trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.substring(7)
            trimmed.contains("://") -> return null
            else -> "https://$trimmed"
        }
        return if (OfflineVideoDownloader.isSafeHttpsSyntaxAndLiteral(withScheme)) withScheme else null
    }

    private fun openUrlInWebView(targetUrl: String, customBrowsing: Boolean) {
        currentPromptedVideoId = ""
        videoPromptRunnable?.let { mainHandler.removeCallbacks(it) }
        videoPromptRunnable = null
        lastSniffedMp4Url = ""
        lastSniffedPageUrl = ""
        customBrowsingActive = customBrowsing
        currentWebUrl = targetUrl
        val player = webView
        if (player == null) {
            initYouTubePlayer(targetUrl)
        } else {
            player.loadUrl(targetUrl)
        }
    }

    private fun showTrialReminder(days: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.trial_reminder_title)
            .setMessage(getString(R.string.trial_reminder_message, days))
            .setCancelable(false)
            .setPositiveButton(R.string.btn_pay) { _, _ -> showProInfoDialog() }
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
            .setMessage(getString(R.string.paywall_message, displayEmail()))
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

    private fun isYouTubeHost(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val host = try {
            Uri.parse(rawUrl).host?.lowercase(Locale.ROOT).orEmpty()
        } catch (_: Exception) {
            ""
        }
        return host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
    }

    private fun handleNavigationUrl(uri: Uri?): Boolean {
        val scheme = uri?.scheme?.lowercase(Locale.ROOT) ?: return true
        val host = uri.host?.lowercase(Locale.ROOT) ?: return true
        if (scheme == "https" && ALLOWED_WEBVIEW_HOSTS.any { host == it || host.endsWith(".$it") }) {
            return false
        }
        if (isSmartphoneDevice() && customBrowsingActive) {
            if (scheme == "https" && OfflineVideoDownloader.isSafeHttpsSyntaxAndLiteral(uri.toString())) {
                return false
            }
            return true
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

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragOrClick(target: View, bar: View, parent: FrameLayout, onClick: () -> Unit) {
        val slop = 8f * resources.displayMetrics.density
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        target.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = bar.x
                    startY = bar.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > slop) {
                        dragging = true
                    }
                    if (dragging) {
                        val maxX = (parent.width - bar.width).coerceAtLeast(0).toFloat()
                        val maxY = (parent.height - bar.height).coerceAtLeast(0).toFloat()
                        bar.x = (startX + dx).coerceIn(0f, maxX)
                        bar.y = (startY + dy).coerceIn(0f, maxY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        v.performClick()
                        onClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun createSmartphoneControlsBar(parent: FrameLayout): LinearLayout {
        val density = resources.displayMetrics.density
        val btnSizePx = (34 * density).toInt()
        val padPx = (4 * density).toInt()
        val leftMarginPx = (10 * density).toInt()
        val bottomMarginPx = (72 * density).toInt()

        val barBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = (20 * density)
            setColor(Color.argb(175, 15, 23, 42))
            setStroke((1 * density).toInt(), Color.argb(95, 255, 255, 255))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = barBg
            alpha = 0.85f
            setPadding(padPx, padPx, padPx, padPx)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                leftMargin = leftMarginPx
                bottomMargin = bottomMarginPx
            }
        }

        val urlBtn = TextView(this).apply {
            text = getString(R.string.btn_url_short)
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.open_link_title)
            layoutParams = LinearLayout.LayoutParams(btnSizePx, btnSizePx)
        }
        val divider = View(this).apply {
            setBackgroundColor(Color.argb(70, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams((1 * density).toInt(), (18 * density).toInt()).apply {
                leftMargin = (2 * density).toInt()
                rightMargin = (2 * density).toInt()
            }
        }
        val settingsBtn = TextView(this).apply {
            text = "\u2699"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.settings_title)
            layoutParams = LinearLayout.LayoutParams(btnSizePx, btnSizePx)
        }

        attachDragOrClick(urlBtn, bar, parent) { showOpenLinkDialog() }
        attachDragOrClick(settingsBtn, bar, parent) { showSettingsDialog() }

        bar.addView(urlBtn)
        bar.addView(divider)
        bar.addView(settingsBtn)
        return bar
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun initYouTubePlayer(initialUrl: String = youtubeStartUrl()) {
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
        if (isSmartphoneDevice()) {
            container.addView(createSmartphoneControlsBar(container))
        } else {
            container.addView(createSettingsOverlayButton())
        }
        setContentView(container)

        player.isFocusable = true
        player.isFocusableInTouchMode = true
        player.requestFocus()

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(player, true)
        }

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
        currentWebUserAgent = settings.userAgentString.orEmpty()

        if (isSmartphoneDevice()) {
            player.addJavascriptInterface(ExternalVideoBridge(), "TubeMuteBridge")
        }

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

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                    isSmartphoneDevice() &&
                    customBrowsingActive
                ) {
                    val reqUrl = request?.url?.toString().orEmpty()
                    if (isLikelyProgressiveMp4Url(reqUrl)) {
                        lastSniffedMp4Url = reqUrl
                        lastSniffedPageUrl = currentWebUrl
                    }
                }
                return null
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                currentWebUrl = url.orEmpty()
                if (isYouTubeHost(url)) {
                    customBrowsingActive = false
                    onYouTubeUrlVisited(url)
                } else if (isSmartphoneDevice()) {
                    videoPromptRunnable?.let { mainHandler.removeCallbacks(it) }
                    videoPromptRunnable = null
                    mainHandler.postDelayed({ injectExternalVideoWatcher() }, 450L)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                currentWebUrl = url.orEmpty()
                if (isYouTubeHost(url)) {
                    customBrowsingActive = false
                    onYouTubeUrlVisited(url)
                } else if (isSmartphoneDevice()) {
                    injectExternalVideoWatcher()
                }
            }
        }
        currentWebUrl = initialUrl
        player.loadUrl(initialUrl)
    }

    private fun isSmartphoneDevice(): Boolean {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return false
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return false
        return true
    }

    private fun extractYouTubeVideoId(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return ""
        val uri = try { Uri.parse(rawUrl) } catch (_: Exception) { return "" }
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (!host.contains("youtube.com") && host != "youtu.be") return ""
        val path = uri.path.orEmpty()
        if (path.startsWith("/watch")) {
            return uri.getQueryParameter("v")?.trim().orEmpty()
        }
        if (path.startsWith("/shorts/")) {
            return path.removePrefix("/shorts/").substringBefore('/').trim()
        }
        if (host == "youtu.be") {
            return path.trimStart('/').substringBefore('/').trim()
        }
        return ""
    }

    private fun onYouTubeUrlVisited(url: String?) {
        if (!isSmartphoneDevice()) return
        val videoId = extractYouTubeVideoId(url)
        if (videoId.isBlank()) {
            currentPromptedVideoId = ""
            videoPromptRunnable?.let { mainHandler.removeCallbacks(it) }
            videoPromptRunnable = null
            return
        }
        if (videoId == currentPromptedVideoId || promptedVideoIds.contains(videoId)) return
        currentPromptedVideoId = videoId
        videoPromptRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (isFinishing || offlineOverlay != null || currentPromptedVideoId != videoId) return@Runnable
            if (promptedVideoIds.contains(videoId)) return@Runnable
            promptedVideoIds.add(videoId)
            showSaveVideoPrompt(videoId)
        }
        videoPromptRunnable = runnable
        mainHandler.postDelayed(runnable, 1800L)
    }

    private fun pauseWebVideoPlayback() {
        webView?.evaluateJavascript(
            "(function(){document.querySelectorAll('video').forEach(function(v){if(!v.paused){v.dataset.tmWasPlaying='1';v.pause();}});})();",
            null
        )
    }

    private fun resumeWebVideoPlayback() {
        webView?.evaluateJavascript(
            "(function(){document.querySelectorAll('video').forEach(function(v){if(v.dataset.tmWasPlaying==='1'){v.dataset.tmWasPlaying='';var p=v.play();if(p&&p.catch)p.catch(function(){});}});})();",
            null
        )
    }

    private fun showSaveVideoPrompt(videoId: String) {
        pauseWebVideoPlayback()
        AlertDialog.Builder(this)
            .setTitle(R.string.save_video_title)
            .setMessage(R.string.save_video_message)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_yes) { _, _ ->
                resumeWebVideoPlayback()
                startOfflineVideoDownload(videoId)
            }
            .setNegativeButton(R.string.btn_no) { _, _ ->
                resumeWebVideoPlayback()
            }
            .show()
    }

    private fun startOfflineVideoDownload(videoId: String) {
        if (downloadingVideoIds.contains(videoId)) return
        downloadingVideoIds.add(videoId)
        Toast.makeText(this, R.string.video_download_started, Toast.LENGTH_SHORT).show()
        thread {
            try {
                val savedUri = OfflineVideoDownloader.download360p(applicationContext, videoId)
                runOnUiThread {
                    downloadingVideoIds.remove(videoId)
                    if (!isFinishing) {
                        showVideoDownloadedDialog(savedUri)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    downloadingVideoIds.remove(videoId)
                    if (!isFinishing) {
                        Toast.makeText(
                            this,
                            getString(R.string.video_download_failed, e.message ?: ""),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun isLikelyProgressiveMp4Url(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val lower = url.lowercase(Locale.ROOT)
        if (lower.contains("youtube.com") || lower.contains("youtu.be") ||
            lower.contains("googlevideo.com") || lower.contains("doubleclick.net") ||
            lower.contains("googlesyndication.com")
        ) {
            return false
        }
        if (lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains(".m4a") ||
            lower.contains("bytestart=") || lower.contains("byteend=") ||
            lower.contains("range=") || lower.contains("dash_") ||
            lower.contains("mime=audio") || lower.contains("mime_type=audio")
        ) {
            return false
        }
        val pathOnly = lower.substringBefore('?')
        return pathOnly.endsWith(".mp4") ||
            lower.contains("mime=video/mp4") ||
            lower.contains("mime=video%2fmp4") ||
            lower.contains("mime_type=video_mp4")
    }

    private fun injectExternalVideoWatcher() {
        val player = webView ?: return
        if (isYouTubeHost(currentWebUrl)) return
        val js = """
            (function() {
              if (location.hostname.indexOf('youtube.') !== -1 || location.hostname === 'youtu.be') return;
              function findMetaOrFbMp4() {
                var metas = ['meta[property="og:video:secure_url"]','meta[property="og:video:url"]','meta[property="og:video"]'];
                for (var i = 0; i < metas.length; i++) {
                  var m = document.querySelector(metas[i]);
                  if (m && m.content && m.content.indexOf('https://') === 0 && m.content.indexOf('.m3u8') === -1) {
                    return m.content;
                  }
                }
                var scripts = document.scripts;
                var keys = ['"browser_native_sd_url":"', '"playable_url":"', '"sd_src_no_ratelimit":"', '"sd_src":"'];
                for (var k = 0; k < keys.length; k++) {
                  var key = keys[k];
                  for (var s = scripts.length - 1; s >= 0; s--) {
                    var txt = scripts[s].textContent || '';
                    var idx = txt.lastIndexOf(key);
                    if (idx !== -1) {
                      var start = idx + key.length;
                      var end = txt.indexOf('"', start);
                      if (end > start && end - start < 2000) {
                        try {
                          var decoded = JSON.parse('"' + txt.slice(start, end) + '"');
                          if (decoded && decoded.indexOf('https://') === 0) return decoded;
                        } catch (e) {}
                      }
                    }
                  }
                }
                return '';
              }
              function inspectVideo(v) {
                if (!v || (v.duration && v.duration < 3)) return;
                var src = v.currentSrc || v.src || '';
                if (!src) {
                  var s = v.querySelector('source');
                  if (s && s.src) src = s.src;
                }
                if (src && src.indexOf('https://') === 0 && src.indexOf('.m3u8') === -1 && src.indexOf('.mpd') === -1) {
                  window.TubeMuteBridge && window.TubeMuteBridge.onVideoCandidate(src, document.title || '', location.href);
                  return;
                }
                var alt = findMetaOrFbMp4();
                if (alt) {
                  window.TubeMuteBridge && window.TubeMuteBridge.onVideoCandidate(alt, document.title || '', location.href);
                } else if (src && src.indexOf('blob:') === 0) {
                  window.TubeMuteBridge && window.TubeMuteBridge.onBlobVideoPlaying(document.title || '', location.href);
                }
              }
              window.__tmScanExternalVideos = function() {
                var vids = document.querySelectorAll('video');
                for (var i = 0; i < vids.length; i++) {
                  if (!vids[i].paused && !vids[i].ended && vids[i].readyState >= 2) {
                    inspectVideo(vids[i]);
                    break;
                  }
                }
              };
              if (!window.__tmExtBound) {
                window.__tmExtBound = true;
                document.addEventListener('play', function(e) {
                  if (e && e.target && e.target.tagName === 'VIDEO') {
                    setTimeout(function() { inspectVideo(e.target); }, 600);
                  }
                }, true);
              }
              window.__tmScanExternalVideos();
            })();
        """.trimIndent()
        player.evaluateJavascript(js, null)
    }

    private inner class ExternalVideoBridge {
        @JavascriptInterface
        fun onVideoCandidate(videoUrl: String?, pageTitle: String?, pageUrl: String?) {
            val media = videoUrl?.trim().orEmpty()
            val title = pageTitle?.trim().orEmpty()
            val page = pageUrl?.trim().orEmpty()
            runOnUiThread {
                handleExternalVideoCandidate(media, title, page)
            }
        }

        @JavascriptInterface
        fun onBlobVideoPlaying(pageTitle: String?, pageUrl: String?) {
            val sniffed = lastSniffedMp4Url
            val page = pageUrl?.trim().orEmpty()
            if (sniffed.isBlank()) return
            val title = pageTitle?.trim().orEmpty()
            runOnUiThread {
                if (lastSniffedPageUrl.isBlank() || sameUrlOrigin(lastSniffedPageUrl, page)) {
                    handleExternalVideoCandidate(sniffed, title, page)
                }
            }
        }
    }

    private fun sameUrlOrigin(a: String, b: String): Boolean {
        return try {
            val ua = Uri.parse(a)
            val ub = Uri.parse(b)
            ua.host?.equals(ub.host, ignoreCase = true) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun handleExternalVideoCandidate(mediaUrl: String, pageTitle: String, pageUrl: String) {
        if (!isSmartphoneDevice() || isFinishing || offlineOverlay != null) return
        if (isYouTubeHost(currentWebUrl) || isYouTubeHost(pageUrl)) return
        if (!OfflineVideoDownloader.isSafeHttpsSyntaxAndLiteral(mediaUrl)) return
        val key = mediaUrl.substringBefore('#')
        if (promptedExternalMediaUrls.contains(key) || downloadingExternalMediaUrls.contains(key)) return
        promptedExternalMediaUrls.add(key)

        videoPromptRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (isFinishing || offlineOverlay != null || isYouTubeHost(currentWebUrl)) return@Runnable
            showSaveExternalVideoPrompt(mediaUrl, pageTitle, pageUrl)
        }
        videoPromptRunnable = runnable
        mainHandler.postDelayed(runnable, 1000L)
    }

    private fun showSaveExternalVideoPrompt(mediaUrl: String, pageTitle: String, pageUrl: String) {
        pauseWebVideoPlayback()
        AlertDialog.Builder(this)
            .setTitle(R.string.save_video_title)
            .setMessage(R.string.save_external_video_message)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_yes) { _, _ ->
                resumeWebVideoPlayback()
                startExternalVideoDownload(mediaUrl, pageTitle, pageUrl)
            }
            .setNegativeButton(R.string.btn_no) { _, _ ->
                resumeWebVideoPlayback()
            }
            .show()
    }

    private fun startExternalVideoDownload(mediaUrl: String, pageTitle: String, pageUrl: String) {
        val key = mediaUrl.substringBefore('#')
        if (downloadingExternalMediaUrls.contains(key)) return
        downloadingExternalMediaUrls.add(key)
        Toast.makeText(this, R.string.video_download_started_mp4, Toast.LENGTH_SHORT).show()
        val ua = currentWebUserAgent
        val referer = pageUrl.ifBlank { currentWebUrl }
        thread {
            try {
                val savedUri = OfflineVideoDownloader.downloadDirectMp4(
                    context = applicationContext,
                    mediaUrl = mediaUrl,
                    pageTitle = pageTitle,
                    referer = referer,
                    userAgent = ua
                )
                runOnUiThread {
                    downloadingExternalMediaUrls.remove(key)
                    if (!isFinishing) {
                        showVideoDownloadedDialog(savedUri)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    downloadingExternalMediaUrls.remove(key)
                    if (!isFinishing) {
                        Toast.makeText(
                            this,
                            getString(R.string.video_download_failed, e.message ?: ""),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun showVideoDownloadedDialog(savedUri: Uri) {
        pauseWebVideoPlayback()
        AlertDialog.Builder(this)
            .setTitle(R.string.video_downloaded_title)
            .setMessage(R.string.video_downloaded_message)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_watch_offline) { _, _ ->
                switchToOfflineVideo(savedUri)
            }
            .setNegativeButton(R.string.btn_continue_watching) { _, _ ->
                resumeWebVideoPlayback()
            }
            .show()
    }

    private fun switchToOfflineVideo(savedUri: Uri) {
        val player = webView
        if (player == null) {
            openOfflineVideoPlayer(savedUri, 0)
            return
        }
        player.evaluateJavascript(
            "(function(){var v=document.querySelector('video');return v?Math.floor(v.currentTime*1000):0;})()"
        ) { raw ->
            val posMs = raw?.trim()?.toIntOrNull() ?: 0
            openOfflineVideoPlayer(savedUri, posMs)
        }
    }

    private fun openOfflineVideoPlayer(videoUri: Uri, startPosMs: Int) {
        if (customView != null) hideCustomView()
        pauseWebVideoPlayback()
        webView?.onPause()
        closeOfflineVideoPlayer(resumeWeb = false)

        val container = rootContainer ?: run {
            launchExternalVideoPlayer(videoUri)
            return
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
        }
        val videoView = VideoView(this)
        val controller = MediaController(this)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
        videoView.setOnPreparedListener { mp ->
            if (startPosMs > 1000 && startPosMs < mp.duration) {
                mp.seekTo(startPosMs)
            }
            mp.start()
        }
        videoView.setOnErrorListener { _, _, _ ->
            closeOfflineVideoPlayer(resumeWeb = true)
            launchExternalVideoPlayer(videoUri)
            true
        }

        val density = resources.displayMetrics.density
        val btnSize = (40 * density).toInt()
        val margin = (14 * density).toInt()
        val closeBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(170, 15, 23, 42))
            setStroke((1 * density).toInt(), Color.argb(120, 255, 255, 255))
        }
        val closeBtn = TextView(this).apply {
            text = "\u2715"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            background = closeBg
            layoutParams = FrameLayout.LayoutParams(btnSize, btnSize, Gravity.TOP or Gravity.END).apply {
                topMargin = margin
                rightMargin = margin
            }
            setOnClickListener { closeOfflineVideoPlayer(resumeWeb = true) }
        }

        overlay.addView(videoView)
        overlay.addView(closeBtn)
        container.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        offlineOverlay = overlay
        offlineVideoView = videoView
        videoView.setVideoURI(videoUri)
        videoView.requestFocus()
    }

    private fun closeOfflineVideoPlayer(resumeWeb: Boolean) {
        offlineVideoView?.stopPlayback()
        offlineVideoView = null
        offlineOverlay?.let { overlay ->
            rootContainer?.removeView(overlay)
        }
        offlineOverlay = null
        if (resumeWeb) {
            webView?.onResume()
            webView?.requestFocus()
        }
    }

    private fun launchExternalVideoPlayer(videoUri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(videoUri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.video_open_error, Toast.LENGTH_LONG).show()
        }
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
        if (keyCode == KeyEvent.KEYCODE_BACK && offlineOverlay != null) {
            closeOfflineVideoPlayer(resumeWeb = true)
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
        if (offlineOverlay != null) { closeOfflineVideoPlayer(resumeWeb = true); return }
        if (customView != null) { hideCustomView(); return }
        val player = webView
        if (player != null && player.canGoBack()) player.goBack() else super.onBackPressed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && customView != null) window.decorView.systemUiVisibility = immersiveSystemUiFlags()
    }

    override fun onDestroy() {
        videoPromptRunnable?.let { mainHandler.removeCallbacks(it) }
        videoPromptRunnable = null
        closeOfflineVideoPlayer(resumeWeb = false)
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
