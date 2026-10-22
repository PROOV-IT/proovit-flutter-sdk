package io.proovit.capture.core

import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import org.json.JSONObject

/** Protocol primitives shared by native consumers. Hash only the final encoded media bytes. */
object CaptureProtocol {
    const val SCHEMA = "proovit.capture.v1"
    const val MAX_MEDIA_BYTES: Long = 100L * 1024L * 1024L
    private const val CAPTURE_DOMAIN = "PROOVIT-CAPTURE-V1\n"
    private const val ENROLLMENT_DOMAIN = "PROOVIT-CAPTURE-ENROLL-V1\n"

    fun canonicalJson(fields: Map<String, String?>): String = fields.toSortedMap().entries.joinToString(",", "{", "}") { (key, value) ->
        quote(key) + ":" + (value?.let(::quote) ?: "null")
    }

    fun captureSigningMessage(canonicalJson: String): ByteArray = (CAPTURE_DOMAIN + canonicalJson).toByteArray(Charsets.UTF_8)

    fun enrollmentSigningMessage(nonce: ByteArray): ByteArray {
        require(nonce.size == 32) { "Enrollment nonce must be 256 bits" }
        return ENROLLMENT_DOMAIN.toByteArray(Charsets.UTF_8) + nonce
    }

    fun validateMediaSize(sizeBytes: Long) {
        require(sizeBytes in 1..MAX_MEDIA_BYTES) { "Media must be between 1 byte and 100 MiB" }
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}

/** Complete Android capture-v1 payload. Only this validated model is accepted by the Keystore signer. */
data class CaptureEnvelope(
    val captureId: String, val companyId: String, val userId: String, val deviceKeyId: String,
    val challengeId: String?, val challengeNonceSha256: String?, val challengeMode: String,
    val captureMethod: String, val mediaSha256: String, val mediaSizeBytes: Long, val mediaMime: String,
    val capturedAtDevice: String, val localSequence: Long,
) {
    fun fields(): Map<String, String?> = linkedMapOf(
        "schema" to CaptureProtocol.SCHEMA, "capture_id" to captureId, "company_id" to companyId,
        "user_id" to userId, "device_key_id" to deviceKeyId, "challenge_id" to challengeId,
        "challenge_nonce_sha256" to challengeNonceSha256, "challenge_mode" to challengeMode,
        "capture_method" to captureMethod, "media_sha256" to mediaSha256,
        "media_size_bytes" to mediaSizeBytes.toString(), "media_mime" to mediaMime,
        "captured_at_device" to capturedAtDevice, "local_sequence" to localSequence.toString(),
    )

    fun canonicalJson(): String {
        val uuidV4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        require(captureId.matches(uuidV4)) { "Invalid capture id" }
        require(companyId.isNotBlank() && userId.isNotBlank() && deviceKeyId.matches(uuidV4)) { "Capture identity fields are required" }
        require(challengeMode in setOf("online", "offline_lease", "none")) { "Invalid challenge mode" }
        if (challengeMode == "none") require(challengeId == null && challengeNonceSha256 == null) { "Challenge fields must be null in none mode" }
        else require(challengeId?.matches(uuidV4) == true && challengeNonceSha256?.matches(Regex("[a-f0-9]{64}")) == true) { "Challenge context is incomplete" }
        require(captureMethod == "android_camerax") { "Android signing is limited to CameraX captures" }
        require(mediaSha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid media hash" }
        CaptureProtocol.validateMediaSize(mediaSizeBytes)
        require(mediaMime in setOf("image/jpeg", "video/mp4", "audio/mp4")) { "Invalid media MIME type" }
        require(capturedAtDevice.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"))) { "Invalid capture timestamp" }
        require(localSequence > 0) { "Invalid local sequence" }
        return CaptureProtocol.canonicalJson(fields())
    }

    companion object {
        fun fromCanonicalJson(canonicalJson: String): CaptureEnvelope {
            require(canonicalJson.length <= 16_384) { "Capture payload is too large" }
            val json = JSONObject(canonicalJson)
            val allowed = setOf("schema", "capture_id", "company_id", "user_id", "device_key_id", "challenge_id", "challenge_nonce_sha256", "challenge_mode", "capture_method", "media_sha256", "media_size_bytes", "media_mime", "captured_at_device", "local_sequence")
            val keys = json.keys().asSequence().toSet()
            require(keys == allowed) { "Capture payload fields are incomplete or unknown" }
            require(json.getString("schema") == CaptureProtocol.SCHEMA) { "Unsupported capture schema" }
            fun nullable(name: String) = if (json.isNull(name)) null else json.getString(name)
            val envelope = CaptureEnvelope(json.getString("capture_id"), json.getString("company_id"), json.getString("user_id"), json.getString("device_key_id"), nullable("challenge_id"), nullable("challenge_nonce_sha256"), json.getString("challenge_mode"), json.getString("capture_method"), json.getString("media_sha256"), json.getString("media_size_bytes").toLong(), json.getString("media_mime"), json.getString("captured_at_device"), json.getString("local_sequence").toLong())
            require(envelope.canonicalJson() == canonicalJson) { "Capture payload is not canonical" }
            return envelope
        }
    }
}

/** No private-key export API is exposed by the headless consumer contract. */
interface CaptureSigner {
    fun publicKeyDer(): ByteArray
    fun signCapture(envelope: CaptureEnvelope): ByteArray
    fun signEnrollment(nonce: ByteArray): ByteArray
}

data class CaptureReceipt(val captureId: String, val scopeId: String, val mediaSha256: String, val mediaSizeBytes: Long, val status: String, val mediaRetained: Boolean)

object CaptureReceiptPolicy {
    fun matchesPending(pending: CaptureReceipt, server: CaptureReceipt): Boolean =
        server.mediaRetained && server.status in setOf("verified", "verified_degraded") &&
            pending.captureId == server.captureId && pending.scopeId == server.scopeId &&
            pending.mediaSha256.equals(server.mediaSha256, ignoreCase = true) &&
            pending.mediaSizeBytes == server.mediaSizeBytes
}

/** Durable observation of a host-supplied receipt; this is deliberately not server authentication. */
class StandaloneReceiptAcknowledgementStore(private val directory: File) {
    data class Observation(val receipt: CaptureReceipt, val recordedAtEpochMs: Long, val alreadyRecorded: Boolean)

    fun record(pending: CaptureReceipt, observed: CaptureReceipt, nowEpochMs: Long = System.currentTimeMillis()): Observation = synchronized(lock) {
        require(pending.captureId.matches(Regex("[0-9a-fA-F-]{36}"))) { "Invalid capture id" }
        require(CaptureReceiptPolicy.matchesPending(pending, observed)) { "Observed receipt does not match pending capture" }
        val normalized = observed.copy(mediaSha256 = observed.mediaSha256.lowercase())
        val file = File(directory, "ack-${pending.captureId}.properties")
        if (file.exists()) {
            val previous = readObservation(file)
            require(previous.receipt == normalized) { "A different receipt observation is already recorded" }
            return@synchronized previous.copy(alreadyRecorded = true)
        }

        check(directory.mkdirs() || directory.isDirectory) { "Unable to create acknowledgement journal" }
        val properties = Properties().apply {
            setProperty("capture_id", normalized.captureId)
            setProperty("scope_id", normalized.scopeId)
            setProperty("media_sha256", normalized.mediaSha256)
            setProperty("media_size_bytes", normalized.mediaSizeBytes.toString())
            setProperty("verification_status", normalized.status)
            setProperty("media_retained", normalized.mediaRetained.toString())
            setProperty("observed_at_epoch_ms", nowEpochMs.toString())
            setProperty("authority", "host_supplied_unverified")
        }
        val temporary = File(directory, "ack-${pending.captureId}.properties.tmp")
        FileOutputStream(temporary).use { output -> properties.store(output, null); output.fd.sync() }
        check(temporary.renameTo(file)) { "Unable to atomically persist receipt observation" }
        Observation(normalized, nowEpochMs, alreadyRecorded = false)
    }

    fun find(captureId: String): CaptureReceipt? {
        require(captureId.matches(Regex("[0-9a-fA-F-]{36}"))) { "Invalid capture id" }
        val file = File(directory, "ack-$captureId.properties")
        return if (file.isFile) readObservation(file).receipt else null
    }

    private fun readObservation(file: File): Observation {
        val properties = Properties().apply { file.inputStream().use(::load) }
        require(properties.getProperty("authority") == "host_supplied_unverified") { "Unknown receipt observation authority" }
        val receipt = CaptureReceipt(
            captureId = properties.getProperty("capture_id") ?: error("Missing capture id"),
            scopeId = properties.getProperty("scope_id") ?: error("Missing scope id"),
            mediaSha256 = properties.getProperty("media_sha256") ?: error("Missing media hash"),
            mediaSizeBytes = properties.getProperty("media_size_bytes")?.toLongOrNull() ?: error("Invalid media size"),
            status = properties.getProperty("verification_status") ?: error("Missing receipt status"),
            mediaRetained = properties.getProperty("media_retained")?.toBooleanStrictOrNull() ?: error("Invalid retention value"),
        )
        return Observation(receipt, properties.getProperty("observed_at_epoch_ms")?.toLongOrNull() ?: error("Invalid observation time"), alreadyRecorded = false)
    }

    companion object { private val lock = Any() }
}
