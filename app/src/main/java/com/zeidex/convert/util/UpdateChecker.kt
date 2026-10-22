package com.zeidex.convert.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

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

object UpdateChecker {

    // Official Zeidexsoft distribution URLs
    const val PRIMARY_MANIFEST_URL = "https://zeidexsoft.com/updates/manifest.json"
    const val FALLBACK_MANIFEST_URL = "https://raw.githubusercontent.com/zeiad6/zeidex-convert-android/main/updates/manifest.json"

    // Official Zeidexsoft APK Release Certificate Fingerprint (SHA-256)
    const val OFFICIAL_CERT_SHA256 = "d24bf9dfb3f1b9acb0f48fb1940b3eacc3875cdb19e7869f4b1281fdecb9366e"

    suspend fun check(context: Context): UpdateState = withContext(Dispatchers.IO) {
        val currentCode = getCurrentVersionCode(context)
        val currentName = getCurrentVersionName(context)

        val manifestJson = fetchManifest()
        if (manifestJson == null) {
            return@withContext UpdateState.Error("Unable to reach zeidexsoft.com update server")
        }

        try {
            val root = JSONObject(manifestJson)
            val packages = root.optJSONObject("packages") ?: root
            val androidPkg = packages.optJSONObject("android")
                ?: packages.optJSONObject("com.zeidex.convert")
                ?: return@withContext UpdateState.Error("No Android package info found in manifest")

            val latestCode = androidPkg.optInt("version_code", 0)
            val latestVersion = androidPkg.optString("version", "1.0.0")
            val downloadUrl = androidPkg.optString("url")
            val sha256 = androidPkg.optString("sha256")
            val signingCert = androidPkg.optString("signing_cert_sha256", OFFICIAL_CERT_SHA256)
            val changelogAr = androidPkg.optString("changelog_ar", "تحديث جديد لتطبيق Zeidex Convert")
            val changelogEn = androidPkg.optString("changelog_en", "New release of Zeidex Convert")
            val releaseDate = androidPkg.optString("release_date", "")
            val mandatory = androidPkg.optBoolean("mandatory", false)

            if (latestCode > currentCode) {
                val info = PackageUpdateInfo(
                    packageId = "com.zeidex.convert",
                    name = "Zeidex Convert Android",
                    version = latestVersion,
                    versionCode = latestCode,
                    downloadUrl = downloadUrl,
                    sha256 = sha256,
                    signingCertSha256 = signingCert,
                    changelogAr = changelogAr,
                    changelogEn = changelogEn,
                    releaseDate = releaseDate,
                    isMandatory = mandatory
                )
                UpdateState.UpdateAvailable(info)
            } else {
                UpdateState.UpToDate(currentName)
            }
        } catch (e: Exception) {
            UpdateState.Error("Error parsing update manifest: ${e.message}")
        }
    }

    private fun fetchManifest(): String? {
        val urls = listOf(PRIMARY_MANIFEST_URL, FALLBACK_MANIFEST_URL)
        for (urlString in urls) {
            try {
                val url = URL(urlString)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 7000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "ZeidexConvert-Android-Updater")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode in 200..299) {
                    val content = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    if (content.isNotBlank()) return content
                }
                conn.disconnect()
            } catch (_: Exception) {
                // Try fallback
            }
        }
        return null
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
            val url = URL(info.downloadUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 20000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ZeidexConvert-Android-Updater")
            }

            val totalBytes = conn.contentLength.toLong()
            var downloadedBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                        onProgress(progress, downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            }
            conn.disconnect()

            // Verify File Checksum SHA-256
            val fileHash = computeFileSha256(destFile)
            if (info.sha256.isNotBlank() && !fileHash.equals(info.sha256.trim(), ignoreCase = true)) {
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
            val matchesExpected = apkCertSha256.equals(expectedCertSha256.trim(), ignoreCase = true)

            // Also check match with currently installed app's certificate
            val currentAppCerts = getCurrentAppSignature(context)
            val matchesCurrent = currentAppCerts != null && currentAppCerts.equals(apkCertSha256, ignoreCase = true)

            val isValid = matchesExpected || matchesCurrent

            SignatureVerificationResult(
                isValid = isValid,
                certSha256 = apkCertSha256,
                fileSha256 = computeFileSha256(apkFile),
                matchesCurrentApp = matchesCurrent,
                message = if (isValid) "Authentic Zeidexsoft signature verified" else "Signature does not match official Zeidex key ($apkCertSha256)"
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
