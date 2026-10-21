package eu.kanade.tachiyomi.ui.mod.appupdate

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.storage.getUriCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID

object ModApkDownload {
    suspend fun download(context: Context, release: ModRelease, progress: (Float?) -> Unit): File = withContext(Dispatchers.IO) {
        context.cacheDir.listFiles()?.filter { it.name.startsWith("mod-update-") && it.extension == "apk" && System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
        val file = File(context.cacheDir, "mod-update-${UUID.randomUUID()}.apk")
        try {
            // Public Drive links must not rely on the publisher's WebView login cookies.
            val cookies = mutableListOf<okhttp3.Cookie>()
            val client = Injekt.get<NetworkHelper>().client.newBuilder().cache(null)
                .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .cookieJar(object : okhttp3.CookieJar {
                    override fun saveFromResponse(url: okhttp3.HttpUrl, received: List<okhttp3.Cookie>) {
                        received.forEach { cookie ->
                            cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                            cookies.add(cookie)
                        }
                    }
                    override fun loadForRequest(url: okhttp3.HttpUrl) = cookies.filter { it.matches(url) }
                }).build()
            var link = release.downloadUrl
            var received = false
            repeat(3) {
                if (received) return@repeat
                client.newCall(Request.Builder().url(link).build()).awaitSuccess().use { response ->
                    val body = response.body
                    val source = body.source()
                    if (source.peek().readUtf8(2) != "PK") {
                        require(body.contentLength() <= 512_000) { "Google Drive did not return an APK" }
                        source.request(512_001)
                        require(source.buffer.size <= 512_000) { "Invalid Google Drive response" }
                        val html = source.readUtf8()
                        link = confirmationLink(html, link, release.fileId)
                            ?: error("Google Drive cannot download this APK. Check link access or try again later.")
                    } else {
                        val total = body.contentLength()
                        require(total <= MAX_APK_BYTES) { "APK is too large" }
                        var downloaded = 0L
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var lastProgress = -1
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                downloaded += count
                                require(downloaded <= MAX_APK_BYTES) { "APK is too large" }
                                output.write(buffer, 0, count)
                                val percent = if (total > 0) (downloaded * 100 / total).toInt() else -1
                                if (percent != lastProgress) {
                                    lastProgress = percent
                                    progress(if (total > 0) downloaded.toFloat() / total else null)
                                }
                            }
                        }
                        require(total < 0 || file.length() == total) { "Download was interrupted. Please retry." }
                        received = true
                    }
                }
            }
            require(received) { "Google Drive did not return the APK" }
            validate(context, file, release)
            file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    const val MAX_APK_BYTES = 300L * 1024 * 1024

    fun confirmationLink(html: String, base: String, fileId: String): String? {
        val form = Jsoup.parse(html, base).selectFirst("form#download-form") ?: return null
        val url = form.absUrl("action").toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.host !in setOf("drive.google.com", "drive.usercontent.google.com")) return null
        val builder = url.newBuilder()
        for (input in form.select("input[name]")) builder.setQueryParameter(input.attr("name"), input.attr("value"))
        val confirmed = builder.build()
        return confirmed.takeIf { it.queryParameter("id") == fileId }?.toString()
    }

    @Suppress("DEPRECATION")
    fun validate(context: Context, file: File, release: ModRelease) {
        val manager = context.packageManager
        val archive = requireNotNull(manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)) { "Downloaded file is not a valid APK" }
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        require(archive.packageName == context.packageName) { "This APK is for a different app variant" }
        require(archive.versionCode > installed.versionCode) { "APK build number must be newer than the installed app" }
        require(archive.versionName == "Mihon Enhanced ${release.version}") { "APK version does not match the published update" }
        val signers = archive.signatures?.toSet().orEmpty()
        require(signers.isNotEmpty() && signers == installed.signatures?.toSet()) { "APK signing key does not match this app" }
    }

    fun install(context: Context, file: File) {
        require(file.isFile) { "APK is no longer cached. Download it again." }
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(file.getUriCompat(context), "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        })
    }
}
