package com.watchnav.packages

import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class RegionManifestVerifierTest {
    @Test
    fun verifiesSignedEnvelopeAndParsesArtifacts() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val payload = JSONObject().apply {
            put("schemaVersion", 1)
            put("packageId", "shanghai-hills")
            put("displayName", "Shanghai Hills")
            put("version", 2)
            put("bounds", listOf(30.0, 120.0, 35.0, 125.0))
            put("artifacts", listOf(JSONObject().apply {
                put("type", "BROUTER_RD5")
                put("id", "E120_N30")
                put("url", "https://example.com/E120_N30.rd5")
                put("sha256", "a".repeat(64))
                put("bytes", 1024)
            }))
        }.toString().toByteArray()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
        val envelope = JSONObject().apply {
            put("keyId", "test")
            put("payload", Base64.getEncoder().encodeToString(payload))
            put("signature", Base64.getEncoder().encodeToString(signature))
        }.toString().toByteArray()

        val manifest = RegionManifestVerifier(mapOf("test" to keyPair.public.encoded)).verify(envelope)

        assertEquals("shanghai-hills", manifest.packageId)
        assertEquals("E120_N30", manifest.artifacts.single().id)
    }
}
