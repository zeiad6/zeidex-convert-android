package com.zeidex.convert.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class PackageUpdateInfo(
    val packageId: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val downloadUrl: String,
    val sha256: String,
    val signingCertSha256: String,
    val changelogAr: String,
    val changelogEn: String,
    val releaseDate: String,
    val isMandatory: Boolean = false
)

data class SignatureVerificationResult(
    val isValid: Boolean,
    val certSha256: String?,
    val fileSha256: String?,
    val matchesCurrentApp: Boolean,
    val message: String
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val info: PackageUpdateInfo) : UpdateState()
    data class UpToDate(val currentVersion: String) : UpdateState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateState()
    data class ReadyToInstall(val apkFile: File, val info: PackageUpdateInfo, val verification: SignatureVerificationResult) : UpdateState()
    data class VerificationFailed(val reason: String) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

internal sealed class ManifestFetch {
    data class Found(val url: String, val json: JSONObject) : ManifestFetch()

    data class Missing(val attempts: List<String>) : ManifestFetch() {
        fun describe(): String = if (attempts.isEmpty()) {
            "No update manifest source is configured"
        } else {
            "No readable update manifest. " + attempts.joinToString("; ")
        }
    }
}

internal data class SignatureVerdict(
    val valid: Boolean,
    val matchesInstalled: Boolean,
    val message: String
)

/**
 * An in-place update is only installable when the downloaded APK carries the same signing
 * certificate as the app already on the device. Android refuses anything else, so accepting a
 * merely "official" key here would download a package the installer then rejects. The pinned
 * key stays a second check: it catches a manifest that points at a foreign build.
 */
internal fun signatureVerdict(
    apkCert: String?,
    installedCert: String?,
    pinnedCert: String
): SignatureVerdict {
    val apk = apkCert?.trim()?.lowercase()
    val installed = installedCert?.trim()?.lowercase()
    val pinned = pinnedCert.trim().lowercase()

    if (apk.isNullOrEmpty()) {
        return SignatureVerdict(false, false, "No cryptographic signature found inside APK")
    }

    val matchesInstalled = !installed.isNullOrEmpty() && apk == installed
    val matchesPinned = apk == pinned

    return when {
        matchesInstalled && matchesPinned ->
            SignatureVerdict(true, true, "Authentic Zeidexsoft signature verified")
        matchesInstalled ->
            SignatureVerdict(
                true,
                true,
                "Signed by the key already installed on this device; the update manifest pins a different key"
            )
        installed.isNullOrEmpty() ->
            SignatureVerdict(
                false,
                false,
                "Could not read the installed app signature; refusing to install an unverified update"
            )
        matchesPinned ->
            SignatureVerdict(
                false,
                false,
                "Signed with the official key, but not the key installed on this device. " +
                    "Android cannot replace the installed app until it is uninstalled."
            )
        else ->
            SignatureVerdict(false, false, "Signature does not match the installed app ($apk)")
    }
}

object UpdateChecker {

    // Official Zeidexsoft distribution URLs
    const val PRIMARY_MANIFEST_URL = "https://zeidexsoft.com/updates/manifest.json"

    // Same unified registry the Rust CLI reads. Both repositories are private today, so this
    // URL only answers once the document is mirrored publicly; the site stays the primary.
    const val FALLBACK_MANIFEST_URL = "https://raw.githubusercontent.com/zeiad6/zeidexfileconventer/main/packaging/updates/manifest.json"

    // Official Zeidexsoft APK Release Certificate Fingerprint (SHA-256)
    const val OFFICIAL_CERT_SHA256 = "d24bf9dfb3f1b9acb0f48fb1940b3eacc3875cdb19e7869f4b1281fdecb9366e"

    private val MANIFEST_URLS = listOf(PRIMARY_MANIFEST_URL, FALLBACK_MANIFEST_URL)
    private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")
    private const val MAX_MANIFEST_CHARS = 512 * 1024
    private const val MAX_APK_BYTES = 300L * 1024 * 1024

    suspend fun check(context: Context): UpdateState = withContext(Dispatchers.IO) {
        val currentCode = getCurrentVersionCode(context)
        val currentName = getCurrentVersionName(context)

        val fetched = when (val result = fetchManifest()) {
            is ManifestFetch.Missing -> return@withContext UpdateState.Error(result.describe())
            is ManifestFetch.Found -> result
        }

        val info = parseManifest(fetched.json).getOrElse { error ->
            return@withContext UpdateState.Error("Invalid update manifest from ${fetched.url}: ${error.message}")
        }

        if (info.versionCode > currentCode) {
            UpdateState.UpdateAvailable(info)
        } else {
            UpdateState.UpToDate(currentName)
        }
    }

    /**
     * Validates one package block before the app offers it. A manifest is only trusted when it
     * names an HTTPS download and a full SHA-256 digest, so a malformed or tampered document can
     * never produce a download that skips checksum verification.
     */
    internal fun parseManifest(root: JSONObject): Result<PackageUpdateInfo> {
        val androidPkg = selectAndroidPackage(root)
            ?: return Result.failure(IllegalArgumentException("no Android package entry"))

        val versionCode = androidPkg.optInt("version_code", 0)
        val version = androidPkg.optString("version").trim()
        val downloadUrl = androidPkg.optString("url").trim()
        val sha256 = androidPkg.optString("sha256").trim()
        val signingCert = androidPkg.optString("signing_cert_sha256", OFFICIAL_CERT_SHA256).trim()

        if (versionCode <= 0) return Result.failure(IllegalArgumentException("version_code must be positive"))
        if (version.isEmpty()) return Result.failure(IllegalArgumentException("version is missing"))
        if (!downloadUrl.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("download URL must use https"))
        }
        if (!SHA256_HEX.matches(sha256)) {
            return Result.failure(IllegalArgumentException("sha256 must be 64 hexadecimal characters"))
        }
        if (!SHA256_HEX.matches(signingCert)) {
            return Result.failure(IllegalArgumentException("signing_cert_sha256 must be 64 hexadecimal characters"))
        }

        return Result.success(
            PackageUpdateInfo(
                packageId = "com.zeidex.convert",
                name = "Zeidex Convert Android",
                version = version,
                versionCode = versionCode,
                downloadUrl = downloadUrl,
                sha256 = sha256.lowercase(),
                signingCertSha256 = signingCert.lowercase(),
                changelogAr = androidPkg.optString("changelog_ar", "تحديث جديد لتطبيق Zeidex Convert"),
                changelogEn = androidPkg.optString("changelog_en", "New release of Zeidex Convert"),
                releaseDate = androidPkg.optString("release_date", ""),
                isMandatory = androidPkg.optBoolean("mandatory", false)
            )
        )
    }

    /**
     * The project keeps one registry for every platform, so `packages` is a JSON array there,
     * while the Android-only document nests the entry under `packages.android`. Both shapes are
     * accepted, and the Android row is selected by platform or package id rather than position.
     */
    private fun selectAndroidPackage(root: JSONObject): JSONObject? {
        when (val packages = root.opt("packages")) {
            is JSONObject -> {
                packages.optJSONObject("android")?.let { return it }
                packages.optJSONObject("com.zeidex.convert")?.let { return it }
            }
            is JSONArray -> {
                for (index in 0 until packages.length()) {
                    val entry = packages.optJSONObject(index) ?: continue
                    if (entry.optString("platform") == "android" ||
                        entry.optString("id") == "com.zeidex.convert"
                    ) {
                        return entry
                    }
                }
            }
        }
        return root.optJSONObject("android")
    }

    /**
     * A 200 response is not proof of a manifest: the marketing site answers unknown paths with
     * its SPA shell. Every candidate is therefore checked for a JSON object body, and a bad
     * source moves on to the next one instead of failing the whole check.
     */
    private fun fetchManifest(): ManifestFetch {
        val attempts = mutableListOf<String>()
        for (urlString in MANIFEST_URLS) {
            try {
                val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 7000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "ZeidexConvert-Android-Updater")
                    setRequestProperty("Accept", "application/json")
                }
                val status = conn.responseCode
                if (status !in 200..299) {
                    attempts += "$urlString -> HTTP $status"
                    conn.disconnect()
                    continue
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val trimmed = body.trim().trimStart('\uFEFF')
                if (trimmed.length > MAX_MANIFEST_CHARS) {
                    attempts += "$urlString -> response too large"
                    continue
                }
                if (!trimmed.startsWith("{")) {
                    attempts += "$urlString -> not a JSON object"
                    continue
                }
                val json = try {
                    JSONObject(trimmed)
                } catch (e: Exception) {
                    attempts += "$urlString -> invalid JSON: ${e.message}"
                    continue
                }
                return ManifestFetch.Found(urlString, json)
            } catch (e: Exception) {
                attempts += "$urlString -> ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        return ManifestFetch.Missing(attempts)
    }

    suspend fun downloadAndVerify(
        context: Context,
        info: PackageUpdateInfo,
        onProgress: (Float, Long, Long) -> Unit
    ): UpdateState = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, "updates")
        if (!updateDir.exists()) updateDir.mkdirs()
        val destFile = File(updateDir, "zeidex_convert_v${info.version}.apk")

        try {
            if (!info.downloadUrl.startsWith("https://")) {
                return@withContext UpdateState.Error("Refusing a non-HTTPS update URL")
            }
            val url = URL(info.downloadUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 20000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ZeidexConvert-Android-Updater")
            }

            val totalBytes = conn.contentLength.toLong()
            if (totalBytes > MAX_APK_BYTES) {
                conn.disconnect()
                return@withContext UpdateState.Error("Update package exceeds the 300 MB limit")
            }
            var downloadedBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (downloadedBytes > MAX_APK_BYTES) {
                            throw IOException("update package exceeded the 300 MB limit")
                        }
                        val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                        onProgress(progress, downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            }
            conn.disconnect()

            // Verify File Checksum SHA-256
            val fileHash = computeFileSha256(destFile)
            if (!fileHash.equals(info.sha256.trim(), ignoreCase = true)) {
                destFile.delete()
                return@withContext UpdateState.VerificationFailed(
                    "SHA-256 Checksum mismatch! Expected: ${info.sha256}, Computed: $fileHash"
                )
            }

            // Verify Cryptographic Signature
            val verification = verifyApkSignature(context, destFile, info.signingCertSha256)
            if (!verification.isValid) {
                destFile.delete()
                return@withContext UpdateState.VerificationFailed(
                    "Security Signature Verification Failed: ${verification.message}"
                )
            }

            UpdateState.ReadyToInstall(destFile, info, verification)
        } catch (e: Exception) {
            destFile.delete()
            UpdateState.Error("Download failed: ${e.localizedMessage}")
        }
    }

    fun verifyApkSignature(
        context: Context,
        apkFile: File,
        expectedCertSha256: String = OFFICIAL_CERT_SHA256
    ): SignatureVerificationResult {
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }

            val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
                ?: return SignatureVerificationResult(
                    isValid = false,
                    certSha256 = null,
                    fileSha256 = computeFileSha256(apkFile),
                    matchesCurrentApp = false,
                    message = "Could not parse APK archive info"
                )

            val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archiveInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                archiveInfo.signatures
            }

            if (signatures.isNullOrEmpty()) {
                return SignatureVerificationResult(
                    isValid = false,
                    certSha256 = null,
                    fileSha256 = computeFileSha256(apkFile),
                    matchesCurrentApp = false,
                    message = "No cryptographic signature found inside APK"
                )
            }

            val apkCertSha256 = computeSignatureSha256(signatures[0])
            val verdict = signatureVerdict(
                apkCert = apkCertSha256,
                installedCert = getCurrentAppSignature(context),
                pinnedCert = expectedCertSha256
            )

            SignatureVerificationResult(
                isValid = verdict.valid,
                certSha256 = apkCertSha256,
                fileSha256 = computeFileSha256(apkFile),
                matchesCurrentApp = verdict.matchesInstalled,
                message = verdict.message
            )
        } catch (e: Exception) {
            SignatureVerificationResult(
                isValid = false,
                certSha256 = null,
                fileSha256 = computeFileSha256(apkFile),
                matchesCurrentApp = false,
                message = "Verification exception: ${e.message}"
            )
        }
    }

    private fun getCurrentAppSignature(context: Context): String? {
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val pkgInfo = pm.getPackageInfo(context.packageName, flags)
            val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.signatures
            }
            if (!signatures.isNullOrEmpty()) {
                computeSignatureSha256(signatures[0])
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun computeSignatureSha256(signature: Signature): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(signature.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buf = ByteArray(64 * 1024)
            var len: Int
            while (fis.read(buf).also { len = it } != -1) {
                digest.update(buf, 0, len)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun launchInstaller(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) {
            1
        }
    }

    fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }
}
