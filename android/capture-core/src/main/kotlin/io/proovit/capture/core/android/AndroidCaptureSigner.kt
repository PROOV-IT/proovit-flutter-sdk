package io.proovit.capture.core.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.proovit.capture.core.CaptureProtocol
import io.proovit.capture.core.CaptureSigner
import io.proovit.capture.core.CaptureEnvelope
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** Android Keystore signer shared by native hosts. Private key material is never exported. */
class AndroidCaptureSigner(context: Context, private val scopeId: String) : CaptureSigner {
    private val preferences = context.getSharedPreferences("proovit_capture_signing_v1", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias get() = "proovit.capture.v1.$scopeId"

    init { require(scopeId.matches(Regex("[a-f0-9]{64}"))) { "Invalid account key scope" } }

    fun installationId(): String = preferences.getString("installation_id_$scopeId", null)
        ?: UUID.randomUUID().toString().lowercase().also {
            check(preferences.edit().putString("installation_id_$scopeId", it).commit()) { "Unable to persist installation id" }
        }

    @Synchronized override fun publicKeyDer(): ByteArray {
        keyStore.getCertificate(alias)?.publicKey?.encoded?.let { return it }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(false).build())
        return generator.generateKeyPair().public.encoded
    }

    @Synchronized override fun signCapture(envelope: CaptureEnvelope): ByteArray = sign(CaptureProtocol.captureSigningMessage(envelope.canonicalJson()))

    @Synchronized override fun signEnrollment(nonce: ByteArray): ByteArray = sign(CaptureProtocol.enrollmentSigningMessage(nonce))

    private fun sign(message: ByteArray): ByteArray {
        publicKeyDer()
        val privateKey = keyStore.getKey(alias, null) as? java.security.PrivateKey
            ?: throw IllegalStateException("Capture signing key is unavailable")
        return Signature.getInstance("SHA256withECDSA").run { initSign(privateKey); update(message); sign() }
    }

    companion object {
        fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        fun encodeBase64Url(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
