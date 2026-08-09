package com.watchnav.packages

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

enum class RegionArtifactType { MAPSFORGE, BROUTER_RD5 }

data class RegionArtifact(
    val type: RegionArtifactType,
    val id: String,
    val url: URL,
    val sha256: String,
    val bytes: Long,
)

data class RegionBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)

data class RegionManifest(
    val packageId: String,
    val displayName: String,
    val version: Long,
    val bounds: RegionBounds,
    val artifacts: List<RegionArtifact>,
    val keyId: String,
) {
    val requiredBytes: Long get() = artifacts.sumOf(RegionArtifact::bytes)
}

class RegionManifestVerifier(trustedKeys: Map<String, ByteArray>) {
    private val keys: Map<String, PublicKey> = trustedKeys.mapValues { (_, bytes) ->
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun verify(envelopeBytes: ByteArray): RegionManifest {
        require(envelopeBytes.size <= MAXIMUM_MANIFEST_BYTES) { "Manifest is too large" }
        val envelope = JSONObject(envelopeBytes.toString(Charsets.UTF_8))
        val keyId = envelope.getString("keyId")
        val key = keys[keyId] ?: error("Manifest signing key is not trusted: $keyId")
        val payload = Base64.getDecoder().decode(envelope.getString("payload"))
        val signatureBytes = Base64.getDecoder().decode(envelope.getString("signature"))
        val valid = Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(payload)
            verify(signatureBytes)
        }
        require(valid) { "Manifest signature is invalid" }
        return parsePayload(payload, keyId)
    }

    private fun parsePayload(payload: ByteArray, keyId: String): RegionManifest {
        val json = JSONObject(payload.toString(Charsets.UTF_8))
        require(json.getInt("schemaVersion") == 1) { "Unsupported manifest schema" }
        val bounds = json.getJSONArray("bounds")
        val artifactsJson = json.getJSONArray("artifacts")
        val artifacts = ArrayList<RegionArtifact>(artifactsJson.length())
        for (index in 0 until artifactsJson.length()) {
            artifacts += parseArtifact(artifactsJson.getJSONObject(index))
        }
        require(artifacts.isNotEmpty()) { "Region package has no artifacts" }
        require(artifacts.count { it.type == RegionArtifactType.MAPSFORGE } <= 1) {
            "Region package can contain only one Mapsforge map"
        }
        return RegionManifest(
            packageId = safeId(json.getString("packageId")),
            displayName = json.getString("displayName").take(MAXIMUM_DISPLAY_NAME_LENGTH),
            version = json.getLong("version").also { require(it > 0) },
            bounds = RegionBounds(
                south = bounds.getDouble(0),
                west = bounds.getDouble(1),
                north = bounds.getDouble(2),
                east = bounds.getDouble(3),
            ),
            artifacts = artifacts,
            keyId = keyId,
        )
    }

    private fun parseArtifact(json: JSONObject): RegionArtifact {
        val type = RegionArtifactType.valueOf(json.getString("type"))
        val id = safeId(json.getString("id"))
        val url = URL(json.getString("url"))
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) {
            "Artifact URL must use HTTPS"
        }
        val sha256 = json.getString("sha256").lowercase()
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256" }
        val bytes = json.getLong("bytes")
        require(bytes in 1..MAXIMUM_ARTIFACT_BYTES) { "Invalid artifact size" }
        return RegionArtifact(type, id, url, sha256, bytes)
    }

    private fun safeId(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid package identifier" }
        return value
    }

    companion object {
        private const val MAXIMUM_MANIFEST_BYTES = 256 * 1024
        private const val MAXIMUM_ARTIFACT_BYTES = 2_500_000_000L
        private const val MAXIMUM_DISPLAY_NAME_LENGTH = 100

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
