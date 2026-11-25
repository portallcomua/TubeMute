package com.custom.reklamoid

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale

object OfflineVideoDownloader {
    data class ResolvedVideo(
        val videoId: String,
        val title: String,
        val streamUrl: String,
        val userAgent: String
    )

    private data class ClientSpec(
        val clientName: String,
        val clientVersion: String,
        val androidSdkVersion: Int,
        val osVersion: String,
        val userAgent: String
    )

    private val CLIENTS = listOf(
        ClientSpec(
            clientName = "ANDROID_VR",
            clientVersion = "1.60.19",
            androidSdkVersion = 32,
            osVersion = "12L",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
        ),
        ClientSpec(
            clientName = "ANDROID",
            clientVersion = "20.10.38",
            androidSdkVersion = 34,
            osVersion = "14",
            userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip"
        )
    )

    fun download360p(context: Context, videoId: String): Uri {
        val resolved = resolve360pMp4(videoId)
        val fileName = sanitizeFileName(resolved.title, resolved.videoId)
        val conn = openStreamWithRedirects(
            url = resolved.streamUrl,
            userAgent = resolved.userAgent,
            referer = "",
            attachWebCookies = false,
            strictHttpsPublic = true
        )
        return saveConnectionStreamToMovies(context, conn, fileName)
    }

    fun downloadDirectMp4(
        context: Context,
        mediaUrl: String,
        pageTitle: String,
        referer: String,
        userAgent: String
    ): Uri {
        if (!isSafeExternalHttpsUrl(mediaUrl)) {
            throw Exception("Unsupported or unsafe video URL")
        }
        val tag = Integer.toHexString(mediaUrl.hashCode()).take(8)
        val fileName = sanitizeFileName(pageTitle.ifBlank { "Video" }, tag)
        val conn = openStreamWithRedirects(
            url = mediaUrl,
            userAgent = userAgent.ifBlank { "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36" },
            referer = referer,
            attachWebCookies = true,
            strictHttpsPublic = true
        )
        val contentType = conn.contentType?.lowercase(Locale.ROOT).orEmpty()
        if (contentType.contains("text/html") ||
            contentType.contains("application/xhtml") ||
            contentType.contains("application/json") ||
            contentType.contains("text/plain")
        ) {
            conn.disconnect()
            throw Exception("Page did not return MP4 stream")
        }
        return saveConnectionStreamToMovies(context, conn, fileName)
    }

    fun isSafeHttpsSyntaxAndLiteral(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val parsed = try {
            URL(rawUrl.trim())
        } catch (_: Exception) {
            return false
        }
        if (!parsed.protocol.equals("https", ignoreCase = true)) return false
        val host = parsed.host?.lowercase(Locale.ROOT)?.trim().orEmpty()
        if (host.isBlank() || !host.contains('.')) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        return !isPrivateOrLoopbackLiteral(host)
    }

    fun isSafeExternalHttpsUrl(rawUrl: String?): Boolean {
        if (!isSafeHttpsSyntaxAndLiteral(rawUrl)) return false
        val host = URL(rawUrl!!.trim()).host.lowercase(Locale.ROOT).trim().trim('[', ']')
        return try {
            val addrs = InetAddress.getAllByName(host)
            addrs.none { addr ->
                addr.isAnyLocalAddress ||
                    addr.isLoopbackAddress ||
                    addr.isLinkLocalAddress ||
                    addr.isSiteLocalAddress ||
                    addr.isMulticastAddress
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isPrivateOrLoopbackLiteral(host: String): Boolean {
        val clean = host.trim('[', ']')
        if (clean == "::1" || clean == "::" || clean.startsWith("fe80:") ||
            clean.startsWith("fc") || clean.startsWith("fd")
        ) {
            return true
        }
        val ipv4Match = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(clean)
        if (ipv4Match != null) {
            val a = ipv4Match.groupValues[1].toIntOrNull() ?: return true
            val b = ipv4Match.groupValues[2].toIntOrNull() ?: return true
            if (a == 0 || a == 10 || a == 127) return true
            if (a == 169 && b == 254) return true
            if (a == 172 && b in 16..31) return true
            if (a == 192 && b == 168) return true
            if (a >= 224) return true
        }
        return false
    }

    private fun resolve360pMp4(videoId: String): ResolvedVideo {
        var lastError = "Stream unavailable"
        for (client in CLIENTS) {
            try {
                val conn = (URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("User-Agent", client.userAgent)
                    setRequestProperty("Origin", "https://www.youtube.com")
                }
                try {
                    val clientObj = JSONObject().apply {
                        put("clientName", client.clientName)
                        put("clientVersion", client.clientVersion)
                        put("androidSdkVersion", client.androidSdkVersion)
                        put("osName", "Android")
                        put("osVersion", client.osVersion)
                    }
                    val body = JSONObject().apply {
                        put("videoId", videoId)
                        put("context", JSONObject().apply { put("client", clientObj) })
                    }
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        lastError = "HTTP $code"
                        continue
                    }
                    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val root = JSONObject(text)
                    val playability = root.optJSONObject("playabilityStatus")
                    val status = playability?.optString("status", "").orEmpty()
                    if (status != "OK") {
                        lastError = playability?.optString("reason", status).orEmpty().ifBlank { status }
                        continue
                    }
                    val title = root.optJSONObject("videoDetails")
                        ?.optString("title", "")
                        .orEmpty()
                        .ifBlank { "TubeMute_$videoId" }
                    val formats = root.optJSONObject("streamingData")?.optJSONArray("formats")
                    if (formats == null || formats.length() == 0) {
                        lastError = "No MP4 stream"
                        continue
                    }
                    var bestUrl = ""
                    var bestItag = Int.MAX_VALUE
                    for (i in 0 until formats.length()) {
                        val fmt = formats.optJSONObject(i) ?: continue
                        val mime = fmt.optString("mimeType", "")
                        val url = fmt.optString("url", "")
                        if (url.isBlank() || !mime.contains("video/mp4")) continue
                        val itag = fmt.optInt("itag", 999)
                        if (itag == 18) {
                            bestUrl = url
                            bestItag = 18
                            break
                        }
                        if (bestUrl.isBlank() || itag < bestItag) {
                            bestUrl = url
                            bestItag = itag
                        }
                    }
                    if (bestUrl.isNotBlank()) {
                        return ResolvedVideo(videoId, title, bestUrl, client.userAgent)
                    }
                    lastError = "No 360p MP4"
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Network error"
            }
        }
        throw Exception(lastError)
    }

    private fun sanitizeFileName(title: String, suffixId: String): String {
        val clean = title.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').take(48)
        val base = clean.ifBlank { "TubeMute" }
        return "${base}_${suffixId}.mp4"
    }

    private fun openStreamWithRedirects(
        url: String,
        userAgent: String,
        referer: String,
        attachWebCookies: Boolean,
        strictHttpsPublic: Boolean
    ): HttpURLConnection {
        var current = url
        for (hop in 0 until 6) {
            if (strictHttpsPublic && !isSafeExternalHttpsUrl(current)) {
                throw Exception("Unsafe redirect URL")
            }
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 60000
                setRequestProperty("User-Agent", userAgent)
                if (referer.isNotBlank() && hop == 0) {
                    setRequestProperty("Referer", referer)
                }
                if (attachWebCookies) {
                    val cookie = try {
                        CookieManager.getInstance().getCookie(current)
                    } catch (_: Exception) {
                        null
                    }
                    if (!cookie.isNullOrBlank()) {
                        setRequestProperty("Cookie", cookie)
                    }
                }
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location").orEmpty()
                conn.disconnect()
                if (loc.isBlank()) throw Exception("Redirect error")
                current = URL(URL(current), loc).toString()
            } else {
                if (code !in 200..299) {
                    conn.disconnect()
                    throw Exception("HTTP $code")
                }
                return conn
            }
        }
        throw Exception("Too many redirects")
    }

    private fun saveConnectionStreamToMovies(
        context: Context,
        conn: HttpURLConnection,
        fileName: String
    ): Uri {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TubeMute")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, values)
                    ?: throw Exception("MediaStore insert failed")
                var totalBytes = 0L
                try {
                    val out = resolver.openOutputStream(itemUri)
                        ?: throw Exception("Cannot open output stream")
                    out.use { output ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(16384)
                            while (true) {
                                val r = input.read(buf)
                                if (r <= 0) break
                                output.write(buf, 0, r)
                                totalBytes += r
                            }
                        }
                    }
                    if (totalBytes < 30_000L) {
                        throw Exception("Empty or incomplete video stream")
                    }
                    val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    resolver.update(itemUri, done, null, null)
                    return itemUri
                } catch (e: Exception) {
                    try { resolver.delete(itemUri, null, null) } catch (_: Exception) {}
                    throw e
                }
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "TubeMute")
                dir.mkdirs()
                val outFile = File(dir, fileName)
                var totalBytes = 0L
                try {
                    FileOutputStream(outFile).use { output ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(16384)
                            while (true) {
                                val r = input.read(buf)
                                if (r <= 0) break
                                output.write(buf, 0, r)
                                totalBytes += r
                            }
                        }
                    }
                    if (totalBytes < 30_000L) {
                        throw Exception("Empty or incomplete video stream")
                    }
                    return Uri.fromFile(outFile)
                } catch (e: Exception) {
                    outFile.delete()
                    throw e
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
