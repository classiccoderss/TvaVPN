package studio.cluvex.aether.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import studio.cluvex.aether.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Checks GitHub Releases for a newer tvaVPN build, downloads it straight into the
 * app's own cache (never through a browser / Downloads folder) and hands it to the
 * system package installer.
 *
 * ## Why this was off since 1.2.2, and what changed
 *
 * `REQUEST_INSTALL_PACKAGES` and an in-app updater were removed in 1.2.2 as the
 * single most over-privileged thing the app could do: silently fetch and install
 * arbitrary code. Re-added on explicit request; this keeps every mitigation that
 * removal bought, a different way, instead of giving them all up:
 *
 *  - the download only ever comes from `api.github.com` / `github.com` over HTTPS;
 *  - before anything is handed to the installer, the downloaded file's package
 *    name and *signing certificate* are checked against the app already installed
 *    ([verifyBeforeInstall]) - a file that is not a signed update of exactly this
 *    app is refused and deleted, never installed;
 *  - installing itself still goes through the OS's own "Install app" confirmation
 *    screen; nothing here can skip that, by platform design;
 *  - the file lives only in [Context.getCacheDir] (never Downloads, never anywhere
 *    another app can browse to) and is deleted (a) immediately on a verification
 *    failure and (b) on the very next process start in any case - which is also
 *    what covers the success path, since Android kills this process to apply an
 *    in-place update ([cleanupLeftoverDownload]).
 *
 * ## What this does NOT protect against
 *
 * [SignerIdentity]'s own doc records finding F-1 as still open by design: the
 * release signing keystore is committed to this repository. A release built with
 * that same key - whoever publishes it - passes the signature check above,
 * because it genuinely *is* the expected signature. This updater is exactly as
 * trustworthy as the GitHub repository and release pipeline it pulls from, no
 * more. [ReleaseInfo.publishedSha256], when the release notes publish one, is the
 * one independent signal shown to the user before install - the same limited
 * mitigation [SignerIdentity] already documents for the copy that is running.
 * Rotating the signing key out of the repository (not this file) is what actually
 * closes F-1.
 */
object UpdateChecker {

    private const val TAG = "update"
    private const val OWNER = "tohidbareza"
    private const val REPO = "tvaVPN"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"
    const val DOWNLOAD_FILE_NAME = "tvavpn-update.apk"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Generous ceiling against a misbehaving / hostile server, not a real size. */
    private const val MAX_APK_BYTES = 200L * 1024 * 1024

    data class ReleaseInfo(
        val tag: String,
        val htmlUrl: String,
        val assetName: String,
        val assetUrl: String,
        val assetSizeBytes: Long,
        val publishedSha256: String?,
    )

    sealed interface CheckResult {
        data class UpToDate(val currentVersion: String) : CheckResult
        data class Available(val release: ReleaseInfo) : CheckResult
        data class Error(val message: String) : CheckResult
    }

    /** Blocking; call off the main thread. One request to the GitHub API. */
    fun checkForUpdate(): CheckResult {
        val release = runCatching { fetchLatestRelease() }
            .getOrElse { return CheckResult.Error(it.message ?: "network error") }
            ?: return CheckResult.Error("no release found")

        return if (isNewer(release.tag, BuildConfig.VERSION_NAME)) {
            CheckResult.Available(release)
        } else {
            CheckResult.UpToDate(BuildConfig.VERSION_NAME)
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo? {
        val conn = (URL(API_LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "tvaVPN")
        }
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val tag = root.optString("tag_name").ifBlank { return null }
            val htmlUrl = root.optString("html_url", RELEASES_PAGE)
            val notes = root.optString("body", "")
            val assets = root.optJSONArray("assets") ?: JSONArray()
            val asset = pickAsset(assets) ?: return null
            val name = asset.optString("name")
            return ReleaseInfo(
                tag = tag,
                htmlUrl = htmlUrl,
                assetName = name,
                assetUrl = asset.optString("browser_download_url"),
                assetSizeBytes = asset.optLong("size", 0L),
                publishedSha256 = findPublishedHash(notes, name),
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Matches this device's ABI to one of the per-ABI split APKs the build
     * publishes (`TvaVPN-<abi>-release.apk`, see `app/build.gradle.kts`),
     * falling back to the universal build and then to any .apk asset at all.
     */
    private fun pickAsset(assets: JSONArray): JSONObject? {
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null

        for (abi in Build.SUPPORTED_ABIS) {
            apks.firstOrNull { it.optString("name").contains(abi, ignoreCase = true) }?.let { return it }
        }
        apks.firstOrNull { it.optString("name").contains("universal", ignoreCase = true) }?.let { return it }
        return apks.first()
    }

    /** Best-effort: a 64-hex-char hash on a line that also names this asset. */
    private fun findPublishedHash(body: String, assetName: String): String? {
        if (assetName.isBlank() || body.isBlank()) return null
        val hex = Regex("[0-9a-fA-F]{64}")
        return body.lineSequence()
            .filter { it.contains(assetName, ignoreCase = true) }
            .firstNotNullOfOrNull { line -> hex.find(line)?.value?.lowercase() }
            // A release with a single APK often prints one unlabeled hash.
            ?: hex.find(body)?.value?.lowercase()
    }

    /** "1.6.0" < "1.7.0"; an unparsable tag just falls back to a plain string compare. */
    private fun isNewer(tag: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
            .split(".", "-", "+").mapNotNull { it.toIntOrNull() }
        val latest = parts(tag)
        val mine = parts(current)
        if (latest.isEmpty() || mine.isEmpty()) return tag.trim() != current.trim()
        for (i in 0 until maxOf(latest.size, mine.size)) {
            val l = latest.getOrElse(i) { 0 }
            val m = mine.getOrElse(i) { 0 }
            if (l != m) return l > m
        }
        return false
    }

    // ------------------------------------------------------------ download

    sealed interface DownloadResult {
        data class Ok(val file: File, val sha256: String) : DownloadResult
        data class Error(val message: String) : DownloadResult
    }

    /** Blocking; call off the main thread. Streams straight to the app cache, nowhere else. */
    fun download(context: Context, release: ReleaseInfo, onProgress: (Int) -> Unit): DownloadResult {
        val target = File(context.cacheDir, DOWNLOAD_FILE_NAME)
        val tmp = File(context.cacheDir, "$DOWNLOAD_FILE_NAME.part")
        val conn = (URL(release.assetUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "tvaVPN")
        }
        return try {
            if (conn.responseCode != 200) return DownloadResult.Error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.assetSizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            tmp.outputStream().use { out ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        written += n
                        if (written > MAX_APK_BYTES) return DownloadResult.Error("file too large")
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        if (total > 0) onProgress(((written * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) return DownloadResult.Error("could not save the download")
            }
            DownloadResult.Ok(target, digest.digest().joinToString("") { "%02x".format(it) })
        } catch (e: Exception) {
            DownloadResult.Error(e.message ?: "download failed")
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    // ------------------------------------------------------------ verify + install

    sealed interface VerifyResult {
        object Ok : VerifyResult
        data class Rejected(val reason: String) : VerifyResult
    }

    /**
     * Refuses to hand the file to the installer unless it parses as an APK for
     * exactly this app's package name, signed with exactly a certificate already
     * installed. Deletes the file on any rejection. See the F-1 note on the class
     * doc for what passing this check does and does not prove.
     */
    fun verifyBeforeInstall(context: Context, apkFile: File): VerifyResult {
        val pm = context.packageManager
        val useSigningInfo = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        val flags = if (useSigningInfo) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: return reject(apkFile, "could not parse the downloaded file as an APK")

        if (archiveInfo.packageName != BuildConfig.APPLICATION_ID) {
            return reject(apkFile, "package name mismatch (${archiveInfo.packageName})")
        }

        val downloadedCerts = certHashes(archiveInfo, useSigningInfo)
        val installedCerts = runCatching {
            certHashes(pm.getPackageInfo(context.packageName, flags), useSigningInfo)
        }.getOrNull()

        if (downloadedCerts.isEmpty() || installedCerts.isNullOrEmpty()) {
            return reject(apkFile, "could not read a signing certificate")
        }
        if (downloadedCerts.intersect(installedCerts.toSet()).isEmpty()) {
            return reject(apkFile, "signing certificate does not match the installed app")
        }
        return VerifyResult.Ok
    }

    private fun reject(apkFile: File, reason: String): VerifyResult {
        apkFile.delete()
        DiagnosticsLog.e(TAG, "Update rejected before install: $reason")
        return VerifyResult.Rejected(reason)
    }

    private fun certHashes(info: PackageInfo, useSigningInfo: Boolean): List<String> {
        val signatures = if (useSigningInfo) {
            val signing = info.signingInfo ?: return emptyList()
            if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory ?: signing.apkContentsSigners
            }
        } else {
            @Suppress("DEPRECATION") info.signatures ?: return emptyList()
        }
        return signatures.map { LanGuard.sha256Hex(it.toByteArray()) }
    }

    /**
     * Fires the system installer. Needs `REQUEST_INSTALL_PACKAGES`, declared in
     * the manifest for this. On API 26+ the user must also have "Install unknown
     * apps" allowed for tvaVPN; this call only starts that flow if needed, it
     * cannot skip the system's own confirmation screen.
     */
    fun install(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun canInstallUnknownApps(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Sends the user to the one system screen that can grant it. */
    fun openUnknownSourcesSettings(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent) }
    }

    fun releasesPageUrl(): String = RELEASES_PAGE

    /**
     * Deletes any update file left in the cache. Called once from
     * `AetherApp.onCreate`, which is also what covers the SUCCESS path: applying
     * an in-place update kills this process, so "after it updates, delete the
     * file" can only ever run on the next start, never the one that triggered it.
     */
    fun cleanupLeftoverDownload(context: Context) {
        listOf(
            File(context.cacheDir, DOWNLOAD_FILE_NAME),
            File(context.cacheDir, "$DOWNLOAD_FILE_NAME.part"),
        ).forEach { it.delete() }
    }
}
