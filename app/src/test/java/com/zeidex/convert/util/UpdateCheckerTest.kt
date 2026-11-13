package com.zeidex.convert.util

import com.zeidex.convert.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateCheckerTest {

    private fun manifestFile(): File {
        val candidates = listOf(File("../updates/manifest.json"), File("updates/manifest.json"))
        return candidates.firstOrNull { it.isFile }
            ?: error("update manifest not found from ${File(".").absolutePath}")
    }

    private fun shippedManifest(): JSONObject = JSONObject(manifestFile().readText())

    private fun androidBlock(root: JSONObject): JSONObject =
        root.getJSONObject("packages").getJSONObject("android")

    private fun validPackage(overrides: Map<String, Any?> = emptyMap()): JSONObject {
        val pkg = JSONObject()
            .put("version", "1.2.0")
            .put("version_code", 3)
            .put("url", "https://example.com/zeidex-convert.apk")
            .put("sha256", "a".repeat(64))
            .put("signing_cert_sha256", "b".repeat(64))
        overrides.forEach { (key, value) ->
            if (value == null) pkg.remove(key) else pkg.put(key, value)
        }
        return JSONObject().put("packages", JSONObject().put("android", pkg))
    }

    private fun rejection(overrides: Map<String, Any?>): String {
        val result = UpdateChecker.parseManifest(validPackage(overrides))
        assertTrue("expected the manifest to be rejected", result.isFailure)
        return result.exceptionOrNull()?.message.orEmpty()
    }

    @Test
    fun shippedManifestMatchesTheBuildVersion() {
        val info = UpdateChecker.parseManifest(shippedManifest()).getOrThrow()

        assertEquals(BuildConfig.VERSION_NAME, info.version)
        assertEquals(BuildConfig.VERSION_CODE, info.versionCode)
    }

    @Test
    fun shippedManifestPinsTheReleaseKeyAndACompleteDigest() {
        val info = UpdateChecker.parseManifest(shippedManifest()).getOrThrow()

        assertEquals(UpdateChecker.OFFICIAL_CERT_SHA256, info.signingCertSha256)
        assertTrue("sha256 must be 64 hexadecimal characters", info.sha256.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun readsTheMirrorUrlWhenPresent() {
        val info = UpdateChecker.parseManifest(
            validPackage(mapOf("mirror_url" to "https://github.com/zeiad6/zeidexfileconventer/releases/download/v0.1.0/zeidex-convert-v1.2.0.apk"))
        ).getOrThrow()

        assertEquals(
            "https://github.com/zeiad6/zeidexfileconventer/releases/download/v0.1.0/zeidex-convert-v1.2.0.apk",
            info.mirrorUrl
        )
    }

    @Test
    fun aMissingMirrorIsNotAnError() {
        val info = UpdateChecker.parseManifest(validPackage()).getOrThrow()

        assertEquals(null, info.mirrorUrl)
    }

    @Test
    fun rejectsAPlainHttpMirror() {
        val message = rejection(mapOf("mirror_url" to "http://example.com/zeidex-convert.apk"))

        assertTrue(message, message.contains("mirror URL must use https"))
    }

    @Test
    fun shippedManifestOnlyAdvertisesHttpsDownloads() {
        val pkg = androidBlock(shippedManifest())

        assertTrue(pkg.getString("url").startsWith("https://"))
        assertTrue(pkg.getString("mirror_url").startsWith("https://"))
    }

    @Test
    fun acceptsAPackageAtTheDocumentRoot() {
        val flat = JSONObject()
            .put("android", validPackage().getJSONObject("packages").getJSONObject("android"))

        assertEquals(3, UpdateChecker.parseManifest(flat).getOrThrow().versionCode)
    }

    @Test
    fun readsTheUnifiedRegistryArray() {
        val linux = JSONObject().put("id", "zeidex-flatpak").put("platform", "linux")
        val android = validPackage().getJSONObject("packages").getJSONObject("android")
            .put("platform", "android")
        val root = JSONObject().put("packages", JSONArray().put(linux).put(android))

        assertEquals(3, UpdateChecker.parseManifest(root).getOrThrow().versionCode)
    }

    @Test
    fun selectsTheAndroidRowByIdWhenThePlatformIsMissing() {
        val other = JSONObject().put("id", "zeidex-convert-windows-x64").put("version_code", 9)
        val android = validPackage().getJSONObject("packages").getJSONObject("android")
            .put("id", "com.zeidex.convert")
        val root = JSONObject().put("packages", JSONArray().put(other).put(android))

        assertEquals("1.2.0", UpdateChecker.parseManifest(root).getOrThrow().version)
    }

    @Test
    fun rejectsAPlainHttpDownload() {
        assertTrue(rejection(mapOf("url" to "http://example.com/zeidex-convert.apk")).contains("https"))
    }

    @Test
    fun rejectsAShortDigest() {
        assertTrue(rejection(mapOf("sha256" to "abc123")).contains("sha256"))
    }

    @Test
    fun rejectsAMissingDigest() {
        assertTrue(rejection(mapOf("sha256" to null)).contains("sha256"))
    }

    @Test
    fun rejectsANonPositiveVersionCode() {
        assertTrue(rejection(mapOf("version_code" to 0)).contains("version_code"))
    }

    @Test
    fun rejectsADocumentWithoutAnAndroidPackage() {
        val root = JSONObject().put("packages", JSONObject())
        val result = UpdateChecker.parseManifest(root)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Android"))
    }

    @Test
    fun acceptsTheKeyAlreadyInstalledOnTheDevice() {
        val verdict = signatureVerdict(
            apkCert = "AB".repeat(32),
            installedCert = "ab".repeat(32),
            pinnedCert = "ab".repeat(32)
        )

        assertTrue(verdict.valid)
        assertTrue(verdict.matchesInstalled)
    }

    @Test
    fun acceptsAnInstalledKeyWhenTheManifestPinIsStale() {
        val verdict = signatureVerdict(
            apkCert = "cd".repeat(32),
            installedCert = "cd".repeat(32),
            pinnedCert = "ab".repeat(32)
        )

        assertTrue(verdict.valid)
        assertTrue(verdict.message.contains("manifest pins a different key"))
    }

    @Test
    fun rejectsTheOfficialKeyWhenTheDeviceCarriesAnotherOne() {
        val verdict = signatureVerdict(
            apkCert = "ab".repeat(32),
            installedCert = "cd".repeat(32),
            pinnedCert = "ab".repeat(32)
        )

        assertFalse(verdict.valid)
        assertFalse(verdict.matchesInstalled)
        assertTrue(verdict.message.contains("uninstalled"))
    }

    @Test
    fun rejectsAKeyThatIsNeitherInstalledNorPinned() {
        val verdict = signatureVerdict(
            apkCert = "ef".repeat(32),
            installedCert = "cd".repeat(32),
            pinnedCert = "ab".repeat(32)
        )

        assertFalse(verdict.valid)
        assertTrue(verdict.message.contains("does not match the installed app"))
    }

    @Test
    fun rejectsAnUnsignedPackage() {
        val verdict = signatureVerdict(apkCert = null, installedCert = "cd".repeat(32), pinnedCert = "ab".repeat(32))

        assertFalse(verdict.valid)
        assertTrue(verdict.message.contains("No cryptographic signature"))
    }

    @Test
    fun rejectsAnUpdateWhenTheInstalledSignatureCannotBeRead() {
        val verdict = signatureVerdict(apkCert = "ab".repeat(32), installedCert = null, pinnedCert = "ab".repeat(32))

        assertFalse(verdict.valid)
        assertTrue(verdict.message.contains("Could not read the installed app signature"))
    }
}
