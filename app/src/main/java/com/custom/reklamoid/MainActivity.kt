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
import android.provider.Settings
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
import java.io.File
import java.io.FileOutputStream
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
        private const val REQ_INSTALL_PERMISSION = 1002
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
    private var pendingInstallApkPath: String? = null
    private var pendingFallbackUrl = ""
    private var progressDialog: AlertDialog? = null

    @Volatile private var requestInProgress = false
    @Volatile private var updateCheckInProgress = false
    @Volatile private var updateDownloadCancelled = false

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
            packageManager.getPackageInfo(packageName, 0).versionName ?: "2.1.2"
        } catch (_: Exception) {
            "2.1.2"
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
        } else if (requestCode == REQ_INSTALL_PERMISSION) {
            val apkPath = pendingInstallApkPath
            val fallback = pendingFallbackUrl
            pendingInstallApkPath = null
            if (!apkPath.isNullOrBlank()) {
                val file = File(apkPath)
                if (file.isFile) {
                    launchSystemInstaller(file, fallback)
                }
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
        val fallbackUrl = releaseUrl.ifBlank { apkUrl }
        AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_message, remoteVersion, appVersionName))
            .setPositiveButton(R.string.btn_download_update) { _, _ ->
                if (apkUrl.isNotBlank()) {
                    downloadAndInstallUpdate(apkUrl, remoteVersion, fallbackUrl)
                } else {
                    openUrlInBrowser(fallbackUrl)
                }
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

    private fun downloadAndInstallUpdate(
        apkUrl: String,
        remoteVersion: String,
        fallbackUrl: String
    ) {
        updateDownloadCancelled = false
        progressDialog?.dismiss()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(R.string.downloading_update)
            .setCancelable(false)
            .setNegativeButton(R.string.btn_later) { _, _ ->
                updateDownloadCancelled = true
            }
            .show()
        progressDialog = dialog

        thread {
            var currentUrl = apkUrl
            try {
                val baseDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    cacheDir
                } else {
                    externalCacheDir ?: cacheDir
                }
                val updatesDir = File(baseDir, "updates").apply { mkdirs() }
                updatesDir.listFiles()?.forEach { old ->
                    if (old.name.endsWith(".apk")) old.delete()
                }
                val safeVer = remoteVersion.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val targetFile = File(updatesDir, "TubeMute-$safeVer.apk")

                var conn: HttpURLConnection? = null
                for (hop in 0 until 6) {
                    val nextConn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 15000
                        readTimeout = 30000
                        setRequestProperty("User-Agent", "TubeMute-Android/$appVersionName")
                        setRequestProperty("Accept", "application/octet-stream")
                    }
                    val code = nextConn.responseCode
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        val location = nextConn.getHeaderField("Location").orEmpty()
                        nextConn.disconnect()
                        if (location.isBlank()) throw Exception("Redirect missing Location")
                        currentUrl = URL(URL(currentUrl), location).toString()
                    } else {
                        conn = nextConn
                        break
                    }
                }
                val activeConn = conn ?: throw Exception("Too many redirects")
                try {
                    val code = activeConn.responseCode
                    if (code !in 200..299) {
                        throw Exception("HTTP $code")
                    }
                    val total = activeConn.contentLengthLong
                    var downloaded = 0L
                    var lastPercent = -1
                    val buffer = ByteArray(16384)
                    activeConn.inputStream.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            while (true) {
                                if (updateDownloadCancelled) {
                                    throw Exception("Cancelled")
                                }
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                if (total > 0) {
                                    val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                    if (pct != lastPercent) {
                                        lastPercent = pct
                                        runOnUiThread {
                                            progressDialog?.setMessage(
                                                getString(
                                                    R.string.downloading_update_progress,
                                                    remoteVersion,
                                                    pct
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (targetFile.length() < 100_000L) {
                        targetFile.delete()
                        throw Exception("Invalid APK file")
                    }
                } finally {
                    activeConn.disconnect()
                }

                runOnUiThread {
                    progressDialog?.dismiss()
                    progressDialog = null
                    if (!updateDownloadCancelled) {
                        installDownloadedApk(targetFile, fallbackUrl)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog?.dismiss()
                    progressDialog = null
                    if (!updateDownloadCancelled) {
                        Toast.makeText(
                            this,
                            getString(R.string.update_download_failed, e.message ?: ""),
                            Toast.LENGTH_LONG
                        ).show()
                        if (fallbackUrl.isNotBlank()) {
                            openUrlInBrowser(fallbackUrl)
                        }
                    }
                }
            }
        }
    }

    private fun installDownloadedApk(file: File, fallbackUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingInstallApkPath = file.absolutePath
            pendingFallbackUrl = fallbackUrl
            AlertDialog.Builder(this)
                .setTitle(R.string.install_permission_title)
                .setMessage(R.string.install_permission_message)
                .setPositiveButton(R.string.btn_open_settings) { _, _ ->
                    try {
                        val settingsIntent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                        @Suppress("DEPRECATION")
                        startActivityForResult(settingsIntent, REQ_INSTALL_PERMISSION)
                    } catch (_: Exception) {
                        pendingInstallApkPath = null
                        launchSystemInstaller(file, fallbackUrl)
                    }
                }
                .setNegativeButton(R.string.btn_later) { _, _ ->
                    pendingInstallApkPath = null
                }
                .show()
            return
        }
        launchSystemInstaller(file, fallbackUrl)
    }

    private fun launchSystemInstaller(file: File, fallbackUrl: String) {
        val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Uri.parse("content://$packageName.updateprovider/updates/${file.name}")
        } else {
            file.setReadable(true, false)
            Uri.fromFile(file)
        }
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(viewIntent)
            return
        } catch (_: Exception) {
        }
        @Suppress("DEPRECATION")
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = apkUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }
        try {
            startActivity(installIntent)
        } catch (_: Exception) {
            if (fallbackUrl.isNotBlank()) {
                openUrlInBrowser(fallbackUrl)
            } else {
                Toast.makeText(this, R.string.update_open_error, Toast.LENGTH_LONG).show()
            }
        }
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
            displayEmail(),
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
        updateDownloadCancelled = true
        progressDialog?.dismiss()
        progressDialog = null
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
