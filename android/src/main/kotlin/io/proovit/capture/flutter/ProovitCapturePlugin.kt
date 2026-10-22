package io.proovit.capture.flutter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import io.proovit.capture.core.CaptureProtocol
import io.proovit.capture.core.android.AndroidCaptureSigner
import io.proovit.capture.core.android.CaptureActivity
import io.proovit.capture.core.android.CaptureJournalStore
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Flutter bridge to the shared CameraX/P-256 core. A private key is never sent over the channel. */
class ProovitCapturePlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler,
    ActivityPluginBinding.OnSaveInstanceStateListener {
    private lateinit var appContext: Context
    private var channel: MethodChannel? = null
    private var activity: Activity? = null
    private var binding: ActivityPluginBinding? = null
    private var pending: MethodChannel.Result? = null
    private var pendingId: String? = null
    private var pendingRequest: PendingCaptureRequest? = null
    private val resultListener = PluginRegistry.ActivityResultListener { requestCode, resultCode, data -> onActivityResult(requestCode, resultCode, data) }

    private data class PendingCaptureRequest(
        val captureId: String, val scope: String, val mediaPath: String, val companyId: String, val actorId: String,
        val deviceKeyId: String, val challengeId: String, val nonceHash: String,
    )

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "io.proovit.capture/methods.v1").also { it.setMethodCallHandler(this) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        this.binding?.removeActivityResultListener(resultListener)
        this.binding?.removeOnSaveStateListener(this)
        channel?.setMethodCallHandler(null); channel = null; activity = null; this.binding = null
        pending?.error("activity_unavailable", "Flutter engine detached during capture", null)
        pending = null; pendingId = null; pendingRequest = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) = attach(binding)
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = attach(binding)
    override fun onDetachedFromActivityForConfigChanges() = detachActivity(configurationChange = true)
    override fun onDetachedFromActivity() = detachActivity(configurationChange = false)

    private fun attach(binding: ActivityPluginBinding) {
        this.binding = binding; activity = binding.activity
        binding.addActivityResultListener(resultListener)
        binding.addOnSaveStateListener(this)
    }

    private fun detachActivity(configurationChange: Boolean) {
        binding?.removeActivityResultListener(resultListener)
        binding?.removeOnSaveStateListener(this)
        binding = null; activity = null
        if (!configurationChange) {
            pending?.error("activity_unavailable", "Android Activity detached during capture; the journal was preserved for recovery", null)
            pending = null; pendingId = null; pendingRequest = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) { (pendingRequest?.captureId ?: pendingId)?.let { outState.putString("proovit.capture.pending_id", it) } }
    // Activity recreation may restore an id but not a MethodChannel callback. The durable core journal is authoritative.
    override fun onRestoreInstanceState(bundle: Bundle?) { pendingId = bundle?.getString("proovit.capture.pending_id") }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "prepareIdentity" -> result.success(prepareIdentity(requireString(call, "identityScope")))
                "signEnrollment" -> {
                    val scope = scopeHash(requireString(call, "identityScope"))
                    val nonce = android.util.Base64.decode(requireString(call, "nonce"), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                    val signer = AndroidCaptureSigner(appContext, scope)
                    result.success(mapOf("signatureDerBase64Url" to AndroidCaptureSigner.encodeBase64Url(signer.signEnrollment(nonce))))
                }
                "capturePhoto" -> launchCapture(call, result)
                "listPending" -> {
                    val scope = scopeHash(requireString(call, "identityScope"))
                    result.success(journal().recover(scope).map { recovery ->
                        val record = recovery.pending
                        mapOf(
                            "captureId" to recovery.captureId, "sha256" to record?.sha256, "sizeBytes" to record?.sizeBytes,
                            "path" to recovery.path, "payload" to record?.canonicalPayload, "signature" to record?.signatureBase64Url,
                            "trust" to if (record != null) "native_signed" else "PARTIAL_RECOVERY", "state" to recovery.state,
                        )
                    })
                }
                "verifyDraft" -> {
                    val scope = scopeHash(requireString(call, "identityScope"))
                    val captureId = requireUuid(call, "captureId")
                    val pendingRecord = journal().listPending(scope).singleOrNull { it.captureId == captureId }
                        ?: error("Capture is absent from this identity scope or its bytes changed")
                    require(pendingRecord.sha256.equals(requireString(call, "sha256"), true)
                        && pendingRecord.sizeBytes == (call.argument<Number>("sizeBytes")?.toLong() ?: -1L)
                        && pendingRecord.canonicalPayload == requireString(call, "payload")
                        && pendingRecord.signatureBase64Url == requireString(call, "signature")) { "Capture draft differs from its atomically journaled bytes" }
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        } catch (error: Throwable) {
            result.error("capture_error", error.message ?: "Native capture error", null)
        }
    }

    private fun prepareIdentity(identityScope: String): Map<String, Any> {
        val signer = AndroidCaptureSigner(appContext, scopeHash(identityScope))
        return mapOf("installationId" to signer.installationId(), "publicKeyDerBase64" to android.util.Base64.encodeToString(signer.publicKeyDer(), android.util.Base64.NO_WRAP))
    }

    private fun launchCapture(call: MethodCall, result: MethodChannel.Result) {
        val host = activity ?: return result.error("activity_unavailable", "A foreground Android Activity is required", null)
        if (pending != null) return result.error("capture_in_progress", "A capture is already in progress", null)
        val identityScope = requireString(call, "identityScope")
        val companyId = requireString(call, "companyId")
        val actorId = requireString(call, "actorId")
        val deviceKeyId = requireUuid(call, "deviceKeyId")
        val challengeId = requireUuid(call, "challengeId")
        val nonce = requireString(call, "challengeNonce")
        require(nonce.matches(Regex("[A-Za-z0-9_-]{40,48}"))) { "Invalid server challenge nonce" }
        val captureId = requireUuid(call, "captureId")
        val scope = scopeHash(identityScope)
        val nonceBytes = android.util.Base64.decode(nonce, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        require(nonceBytes.size == 32) { "Online capture nonce must be 32 bytes" }
        val nonceHash = MessageDigest.getInstance("SHA-256").digest(nonceBytes).toHex()
        val preferences = appContext.getSharedPreferences("proovit_flutter_capture_v1", Context.MODE_PRIVATE)
        val counterKey = "sequence_$scope"
        val sequence = preferences.getLong(counterKey, 0L) + 1L
        check(preferences.edit().putLong(counterKey, sequence).commit()) { "Unable to persist capture sequence" }
        val mediaDir = File(appContext.filesDir, "proovit-captures").apply { check(mkdirs() || isDirectory) }
        val media = File(mediaDir, "proovit_photo_$captureId.jpg")
        journal().reserve(captureId, scope, media)
        pending = result; pendingId = captureId
        pendingRequest = PendingCaptureRequest(captureId, scope, media.canonicalPath, companyId, actorId, deviceKeyId, challengeId, nonceHash)
        try {
            host.startActivityForResult(Intent(host, CaptureActivity::class.java).apply {
                putExtra(CaptureActivity.EXTRA_KIND, "photo")
                putExtra(CaptureActivity.EXTRA_MAX_BYTES, CaptureProtocol.MAX_MEDIA_BYTES)
                putExtra(CaptureActivity.EXTRA_RECOVERY_ID, captureId)
                putExtra(CaptureActivity.EXTRA_RECOVERY_SCOPE, scope)
                putExtra(CaptureActivity.EXTRA_CORE_PIPELINE, true)
                putExtra(CaptureActivity.EXTRA_COMPANY_ID, companyId)
                putExtra(CaptureActivity.EXTRA_USER_ID, actorId)
                putExtra(CaptureActivity.EXTRA_DEVICE_KEY_ID, deviceKeyId)
                putExtra(CaptureActivity.EXTRA_CHALLENGE_ID, challengeId)
                putExtra(CaptureActivity.EXTRA_CHALLENGE_NONCE_SHA256, nonceHash)
                putExtra(CaptureActivity.EXTRA_LOCAL_SEQUENCE, sequence)
            }, REQUEST_CAPTURE)
        } catch (error: Throwable) {
            // Keep the durable reservation for partial-recovery UX, but never leave a Dart Future hanging.
            pending = null; pendingId = null; pendingRequest = null
            result.error("activity_unavailable", error.message ?: "Unable to start CameraX capture", null)
        }
    }

    private fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CAPTURE) return false
        val callback = pending
        val request = pendingRequest
        if (callback == null || request == null) return true
        if (resultCode != Activity.RESULT_OK) {
            val error = data?.getStringExtra(CaptureActivity.EXTRA_ERROR)
            if (error != null) callback.error("capture_failed", error, null) else callback.success(mapOf("cancelled" to true, "trust" to "SIMULATED"))
            pending = null; pendingId = null; pendingRequest = null
            return true
        }
        try {
            val resultData = data ?: error("Capture result is missing")
            val media = File(resultData.getStringExtra("path") ?: error("Capture media path is missing"))
            val payload = resultData.getStringExtra("capture_envelope_canonical_json") ?: error("Signed payload is missing")
            val signature = resultData.getStringExtra("signature_der_base64url") ?: error("Capture signature is missing")
            val captureId = resultData.getStringExtra("capture_id") ?: error("Capture id is missing")
            val envelope = io.proovit.capture.core.CaptureEnvelope.fromCanonicalJson(payload)
            val returnedScope = resultData.getStringExtra("capture_scope_id") ?: error("Capture scope missing")
            require(captureId == request.captureId && returnedScope == request.scope && media.canonicalPath == request.mediaPath &&
                envelope.captureId == request.captureId && envelope.companyId == request.companyId && envelope.userId == request.actorId &&
                envelope.deviceKeyId == request.deviceKeyId && envelope.challengeId == request.challengeId &&
                envelope.challengeNonceSha256 == request.nonceHash && envelope.challengeMode == "online" && envelope.captureMethod == "android_camerax" &&
                envelope.mediaMime == "image/jpeg" && media.isFile && media.length() == envelope.mediaSizeBytes) { "Capture identity, challenge, path or media size mismatch" }
            val signer = AndroidCaptureSigner(appContext, request.scope)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(signer.publicKeyDer()))
            val sigBytes = android.util.Base64.decode(signature, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            check(Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey); update(CaptureProtocol.captureSigningMessage(envelope.canonicalJson())); verify(sigBytes) }) { "Local P-256 signature verification failed" }
            val saved = journal().listPending(request.scope).single { it.captureId == request.captureId }
            require(saved.path == request.mediaPath && saved.canonicalPayload == payload && saved.signatureBase64Url == signature &&
                saved.sha256.equals(envelope.mediaSha256, true) && saved.sizeBytes == envelope.mediaSizeBytes) { "Capture differs from its reserved journal record" }
            callback.success(mapOf("cancelled" to false, "captureId" to request.captureId, "path" to saved.path, "sha256" to saved.sha256, "sizeBytes" to saved.sizeBytes, "mimeType" to envelope.mediaMime, "payload" to payload, "signature" to signature, "publicKeyDerBase64" to android.util.Base64.encodeToString(signer.publicKeyDer(), android.util.Base64.NO_WRAP), "identityScope" to request.scope, "companyId" to request.companyId, "actorId" to request.actorId, "deviceKeyId" to request.deviceKeyId, "trust" to "native_signed", "localSignatureVerified" to true))
        } catch (error: Throwable) { callback.error("capture_integrity_failed", error.message ?: "Capture integrity check failed", null) }
        finally { pending = null; pendingId = null; pendingRequest = null }
        return true
    }

    private fun journal() = CaptureJournalStore(File(appContext.filesDir, "capture-journal"))
    private fun scopeHash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun requireString(call: MethodCall, key: String) = (call.argument<String>(key) ?: error("Missing $key")).also { require(it.isNotBlank()) }
    private fun requireUuid(call: MethodCall, key: String) = requireString(call, key).also { require(it.matches(UUID_PATTERN)) { "Invalid $key" } }

    companion object { private const val REQUEST_CAPTURE = 7314; private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}") }
}
