package io.proovit.capture.core.android

import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import io.proovit.capture.core.CaptureProtocol

/** Atomic host-local journal for finalized media. The media file is always retained. */
class CaptureJournalStore(private val directory: File) {
    data class Pending(val captureId: String, val scopeId: String, val sha256: String, val sizeBytes: Long, val path: String, val canonicalPayload: String? = null, val signatureBase64Url: String? = null)
    data class Recovery(val captureId: String, val scopeId: String, val path: String, val state: String, val pending: Pending? = null)

    @Synchronized fun reserve(captureId: String, scopeId: String, mediaPath: File) {
        require(captureId.matches(Regex("[0-9a-fA-F-]{36}")) && scopeId.matches(Regex("[a-f0-9]{64}"))) { "Invalid recovery identity" }
        check(directory.mkdirs() || directory.isDirectory) { "Unable to create capture journal" }
        val target = File(directory, "reservation-$captureId.properties")
        if (target.exists()) {
            val previous = readProperties(target)
            require(previous.getProperty("scope_id") == scopeId && previous.getProperty("path") == mediaPath.canonicalPath) { "Capture reservation conflicts with saved identity" }
            return
        }
        writeProperties(target, Properties().apply {
            setProperty("capture_id", captureId); setProperty("scope_id", scopeId)
            setProperty("path", mediaPath.canonicalPath); setProperty("state", "reserved")
        })
    }

    @Synchronized fun append(record: Pending) {
        require(record.captureId.matches(Regex("[0-9a-fA-F-]{36}"))) { "Invalid capture id" }
        require(record.scopeId.matches(Regex("[a-f0-9]{64}"))) { "Invalid scope" }
        require(record.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid media hash" }
        CaptureProtocol.validateMediaSize(record.sizeBytes)
        val media = File(record.path).canonicalFile
        require(media.isFile && media.length() == record.sizeBytes && sha256(media).equals(record.sha256, true)) { "Media integrity mismatch" }
        check(directory.mkdirs() || directory.isDirectory) { "Unable to create capture journal" }
        val target = File(directory, "capture-${record.captureId}.properties")
        if (target.exists()) {
            require(read(target) == record) { "Capture id is already journaled with different data" }
            File(directory, "reservation-${record.captureId}.properties").delete()
            return
        }
        val temp = File(directory, "capture-${record.captureId}.properties.tmp")
        writeProperties(temp, Properties().apply {
                setProperty("capture_id", record.captureId); setProperty("scope_id", record.scopeId)
                setProperty("sha256", record.sha256.lowercase()); setProperty("size_bytes", record.sizeBytes.toString())
                setProperty("path", media.absolutePath)
                record.canonicalPayload?.let { setProperty("canonical_payload", it) }
                record.signatureBase64Url?.let { setProperty("signature_der_base64url", it) }
            }, temp = true)
        check(temp.renameTo(target)) { "Unable to atomically persist capture journal" }
        File(directory, "reservation-${record.captureId}.properties").delete()
    }

    @Synchronized fun completeReserved(record: Pending) {
        val reservation = File(directory, "reservation-${record.captureId}.properties")
        require(reservation.isFile) { "Capture has no pre-capture recovery reservation" }
        val reserved = readProperties(reservation)
        require(reserved.getProperty("scope_id") == record.scopeId && reserved.getProperty("path") == File(record.path).canonicalPath) {
            "Final capture does not match its pre-capture reservation"
        }
        append(record)
    }

    @Synchronized fun cancelReservation(captureId: String, scopeId: String, mediaPath: File) {
        val file = File(directory, "reservation-$captureId.properties")
        if (!file.isFile) return
        val reservation = readProperties(file)
        require(reservation.getProperty("scope_id") == scopeId && reservation.getProperty("path") == mediaPath.canonicalPath) {
            "Reservation identity mismatch"
        }
        require(!mediaPath.exists()) { "Cannot cancel a reservation while its original media exists" }
        check(file.delete()) { "Unable to remove cancelled capture reservation" }
    }

    @Synchronized fun listPending(scopeId: String): List<Pending> {
        require(scopeId.matches(Regex("[a-f0-9]{64}"))) { "Invalid scope" }
        return directory.listFiles()?.filter { it.name.matches(Regex("capture-[0-9a-fA-F-]{36}\\.properties")) }
            ?.mapNotNull { runCatching { read(it) }.getOrNull() }
            ?.filter { it.scopeId == scopeId && File(it.path).isFile && File(it.path).length() == it.sizeBytes && sha256(File(it.path)).equals(it.sha256, true) }
            .orEmpty()
    }

    @Synchronized fun recover(scopeId: String): List<Recovery> {
        require(scopeId.matches(Regex("[a-f0-9]{64}"))) { "Invalid scope" }
        val completed = listPending(scopeId).map { Recovery(it.captureId, it.scopeId, it.path, "RECOVERABLE", it) }
        val completedIds = completed.mapTo(mutableSetOf()) { it.captureId }
        val reservations = directory.listFiles()?.filter { it.name.matches(Regex("reservation-[0-9a-fA-F-]{36}\\.properties")) }
            ?.mapNotNull { file -> runCatching {
                val p = readProperties(file)
                val id = p.getProperty("capture_id")
                if (p.getProperty("scope_id") != scopeId || id in completedIds) null else Recovery(id, scopeId, p.getProperty("path"), "PARTIAL_RECOVERY")
            }.getOrNull() }.orEmpty()
        return completed + reservations
    }

    private fun read(file: File): Pending {
        val p = readProperties(file)
        return Pending(p.getProperty("capture_id") ?: error("Missing capture id"), p.getProperty("scope_id") ?: error("Missing scope"), p.getProperty("sha256") ?: error("Missing hash"), p.getProperty("size_bytes")?.toLongOrNull() ?: error("Invalid size"), p.getProperty("path") ?: error("Missing path"), p.getProperty("canonical_payload"), p.getProperty("signature_der_base64url"))
    }

    private fun readProperties(file: File) = Properties().apply { file.inputStream().use(::load) }

    private fun writeProperties(target: File, properties: Properties, temp: Boolean = false) {
        val outputFile = if (temp) target else File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(outputFile).use { output -> properties.store(output, null); output.fd.sync() }
        if (!temp) check(outputFile.renameTo(target)) { "Unable to atomically persist capture recovery record" }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
