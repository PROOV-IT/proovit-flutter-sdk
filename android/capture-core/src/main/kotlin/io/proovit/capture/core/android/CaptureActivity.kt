package io.proovit.capture.core.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.ExifInterface
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import io.proovit.capture.core.CaptureEnvelope

/**
 * Native media capture activity.
 *
 * Video uses CameraX VideoCapture and writes directly to the final MP4 file. There
 * is no high-resolution source followed by a JavaScript/native transcode. Audio
 * remains on MediaRecorder because this activity only needs a small AAC/M4A path
 * for audio and CameraX does not provide an audio-only recorder.
 */
class CaptureActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ProovitMediaCapture"
        const val EXTRA_KIND = "kind"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_MAX_DURATION_SECONDS = "maxDurationSeconds"
        const val EXTRA_MAX_BYTES = "maxBytes"
        const val EXTRA_CAMERA_POSITION = "cameraPosition"
        const val EXTRA_RECOVERY_SCOPE = "capture_recovery_scope"
        const val EXTRA_RECOVERY_ID = "capture_recovery_id"
        const val EXTRA_CORE_PIPELINE = "capture_core_pipeline"
        const val EXTRA_COMPANY_ID = "capture_company_id"
        const val EXTRA_USER_ID = "capture_user_id"
        const val EXTRA_DEVICE_KEY_ID = "capture_device_key_id"
        const val EXTRA_CHALLENGE_ID = "capture_challenge_id"
        const val EXTRA_CHALLENGE_NONCE_SHA256 = "capture_challenge_nonce_sha256"
        const val EXTRA_LOCAL_SEQUENCE = "capture_local_sequence"

        const val EXTRA_ERROR = "error"
        const val RESULT_OK = Activity.RESULT_OK

        private const val PERMISSION_REQUEST_CODE = 7001
        private const val COMPLIANCE_CHECK_MS = 500L
    }

    private data class VideoProfile(
        val quality: List<Quality>,
        val width: Int,
        val height: Int,
        val fps: Int,
        val videoBitrate: Int,
        val photoMaxDimension: Int,
        val photoQuality: Int,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var kind = "video"
    private var profileName = "standard"
    private var maxDurationSeconds = 600
    private var maxBytes = 95L * 1024L * 1024L
    private var cameraPosition = "back"
    private var outputFile: File? = null
    private var startedAtMs: Long = 0L
    private var mediaRecorder: MediaRecorder? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private var videoRecording: Recording? = null
    private var previewView: PreviewView? = null
    private var recording = false
    private var finalized = false
    private var permissionsGranted = false
    private var previewReady = false
    private var cameraInitializationStarted = false
    private var reviewReady = false
    private var captureDurationMs = 0L
    private var captureStopReason = "user"
    private var stopRequested = false
    private var lastRecordedBytes = 0L
    private var recordedVideoWidth: Int? = null
    private var recordedVideoHeight: Int? = null
    private var recordedVideoFps: Int? = null
    private var capturedPhotoWidth: Int? = null
    private var capturedPhotoHeight: Int? = null
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var reviewControls: LinearLayout
    private lateinit var cancelButton: Button
    private lateinit var confirmButton: Button

    private val complianceCheck = object : Runnable {
        override fun run() {
            if (!recording) return

            val elapsed = (System.currentTimeMillis() - startedAtMs) / 1000L
            val bytes = maxOf(lastRecordedBytes, outputFile?.length() ?: 0L)
            statusText.text = String.format(
                Locale.getDefault(),
                "%02d:%02d · %d MB",
                elapsed / 60,
                elapsed % 60,
                bytes / (1024 * 1024),
            )

            if (elapsed >= maxDurationSeconds) {
                stopCaptureForReview("max_duration")
                return
            }

            // CameraX also enforces this limit at the output layer. This check is
            // kept as a safety net for devices that report file size late.
            if (bytes >= maxBytes) {
                stopCaptureForReview("max_bytes")
                return
            }

            handler.postDelayed(this, COMPLIANCE_CHECK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        kind = intent.getStringExtra(EXTRA_KIND)?.lowercase() ?: "video"
        profileName = intent.getStringExtra(EXTRA_PROFILE)?.lowercase() ?: "standard"
        maxDurationSeconds = intent.getIntExtra(EXTRA_MAX_DURATION_SECONDS, 600).coerceAtLeast(1)
        maxBytes = intent.getLongExtra(EXTRA_MAX_BYTES, 95L * 1024L * 1024L).coerceAtLeast(1L)
        cameraPosition = intent.getStringExtra(EXTRA_CAMERA_POSITION)?.lowercase() ?: "back"

        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (recording) {
                    failCapture("Capture cancelled")
                } else if (reviewReady) {
                    cancelCapture()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val permissions = mutableListOf<String>()
        if (kind == "photo" || kind == "video") permissions += Manifest.permission.CAMERA
        if (kind == "video" || kind == "audio") permissions += Manifest.permission.RECORD_AUDIO

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            permissionsGranted = true
            prepareCapture()
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        if (kind == "photo" || kind == "video") {
            previewView = PreviewView(this).also {
                it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                it.scaleType = PreviewView.ScaleType.FILL_CENTER
                root.addView(it, FrameLayout.LayoutParams(-1, -1))
            }
        }

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            text = when (kind) {
                "photo" -> "Préparation de la photo…"
                "video" -> "Préparation de la vidéo…"
                else -> "Prêt à enregistrer un audio"
            }
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = roundedBackground(0xAA000000.toInt(), 0)
        }
        root.addView(statusText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(28)
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(18), dp(24), dp(42))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00101010, 0xEE101010.toInt()),
            )
        }

        actionButton = Button(this).apply {
            text = "●"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Démarrer l’enregistrement"
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minHeight = 0
            isEnabled = false
            setOnClickListener {
                when {
                    recording -> stopCaptureForReview("user")
                    kind == "photo" -> capturePhoto()
                    kind == "video" -> startVideoRecording()
                    else -> startAudioRecording()
                }
            }
        }
        controls.addView(actionButton, LinearLayout.LayoutParams(dp(88), dp(88)).apply {
            topMargin = dp(12)
        })

        reviewControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        cancelButton = makeTextButton("Annuler") { cancelCapture() }
        confirmButton = makeTextButton(
            when (kind) {
                "photo" -> "Utiliser la photo"
                "video" -> "Utiliser la vidéo"
                else -> "Utiliser l’audio"
            },
        ) {
            confirmCapture()
        }
        reviewControls.addView(cancelButton)
        reviewControls.addView(confirmButton)
        controls.addView(reviewControls, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(16)
        })

        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun makeTextButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        setOnClickListener { onClick() }
        background = roundedBackground(0xCC303030.toInt(), 0x66FFFFFF)
        setPadding(dp(16), dp(4), dp(16), dp(4))
        layoutParams = LinearLayout.LayoutParams(-2, dp(48)).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        }
    }

    private fun roundedBackground(color: Int, strokeColor: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(24).toFloat()
        setColor(color)
        if (strokeColor != 0) setStroke(dp(1), strokeColor)
    }

    private fun roundButtonBackground(recording: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (recording) 0xFFB91C1C.toInt() else 0xFFE11D48.toInt())
        setStroke(dp(5), Color.WHITE)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE || grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            failCapture("Camera or microphone permission was denied")
            return
        }
        permissionsGranted = true
        prepareCapture()
    }

    private fun prepareCapture() {
        if (!permissionsGranted || finalized) return

        if (kind == "audio") {
            actionButton.background = roundButtonBackground(recording = false)
            actionButton.contentDescription = "Démarrer l’enregistrement"
            actionButton.isEnabled = true
            return
        }

        if (cameraInitializationStarted || previewReady) return
        val preview = previewView
        if (preview == null) {
            failCapture("Video preview is unavailable")
            return
        }

        cameraInitializationStarted = true
        statusText.text = "Préparation de la caméra…"
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                bindCameraUseCases(cameraProviderFuture.get())
            } catch (error: Exception) {
                Log.e(TAG, "Unable to prepare CameraX preview", error)
                failCapture(error.message ?: "Unable to prepare video preview")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(provider: ProcessCameraProvider) {
        val preview = previewView ?: throw IllegalStateException("Camera preview is unavailable")
        val profile = profileFor(profileName)
        val selector = CameraSelector.Builder()
            .requireLensFacing(
                if (cameraPosition == "front") CameraSelector.LENS_FACING_FRONT
                else CameraSelector.LENS_FACING_BACK,
            )
            .build()

        if (!provider.hasCamera(selector)) {
            throw IllegalStateException("Requested camera is not available")
        }

        val previewUseCase = Preview.Builder().build().also {
            it.setSurfaceProvider(preview.surfaceProvider)
        }

        provider.unbindAll()
        cameraProvider = provider

        if (kind == "photo") {
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(profile.photoQuality)
                .build()
            provider.bindToLifecycle(this, selector, previewUseCase, capture)
            imageCapture = capture
            previewReady = true
            actionButton.background = roundButtonBackground(recording = false)
            actionButton.contentDescription = "Prendre une photo"
            actionButton.isEnabled = true
            statusText.text = "Aperçu prêt · appuyez pour prendre la photo"
            return
        }

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.fromOrderedList(profile.quality))
            .setTargetVideoEncodingBitRate(profile.videoBitrate)
            .build()
        val capture = VideoCapture.withOutput(recorder)
        provider.bindToLifecycle(this, selector, previewUseCase, capture)
        videoCapture = capture
        // CameraX exposes the selected quality through the recorder profile;
        // VideoCapture itself does not provide a resolutionInfo property.
        recordedVideoWidth = profile.width
        recordedVideoHeight = profile.height
        recordedVideoFps = profile.fps
        previewReady = true
        actionButton.background = roundButtonBackground(recording = false)
        actionButton.contentDescription = "Démarrer l’enregistrement"
        actionButton.isEnabled = true
        statusText.text = "Aperçu prêt · appuyez pour enregistrer"
        Log.i(
            TAG,
            "CameraX preview ready: ${recordedVideoWidth}x${recordedVideoHeight}, target ${profile.videoBitrate} bps",
        )
    }

    private fun capturePhoto() {
        if (finalized || !previewReady || reviewReady) return
        val capture = imageCapture ?: run {
            failCapture("The photo camera is no longer available")
            return
        }

        val file = createOutputFile(".jpg")
        outputFile = file
        actionButton.isEnabled = false
        statusText.text = "Prise de la photo…"
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    try {
                        processPhoto(file)
                        releaseCaptureResources(deleteOutput = false)
                        showReview(file, "Aperçu prêt · vérifiez votre photo")
                    } catch (error: Exception) {
                        Log.e(TAG, "Unable to finalize photo capture", error)
                        releaseCaptureResources(deleteOutput = true)
                        failCapture(error.message ?: "Unable to finalize photo capture")
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Unable to capture photo", exception)
                    releaseCaptureResources(deleteOutput = true)
                    failCapture(exception.message ?: "Unable to capture photo")
                }
            },
        )
    }

    private fun processPhoto(file: File) {
        val profile = profileFor(profileName)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("Captured photo has invalid dimensions")

        val scale = minOf(1f, profile.photoMaxDimension.toFloat() / maxOf(bounds.outWidth, bounds.outHeight).toFloat())
        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, profile.photoMaxDimension)
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: throw IllegalStateException("Captured photo could not be decoded")
        val targetWidth = maxOf(1, (bounds.outWidth * scale).roundToInt())
        val targetHeight = maxOf(1, (bounds.outHeight * scale).roundToInt())
        val resized = if (decoded.width != targetWidth || decoded.height != targetHeight) {
            Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        val orientation = ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val oriented = orientBitmap(resized, orientation)
        if (oriented !== resized) resized.recycle()

        var bitmapToCompress = oriented
        var quality = profile.photoQuality
        var compressed: ByteArray? = null
        var attempts = 0
        while (compressed == null || compressed!!.size > maxBytes) {
            val output = ByteArrayOutputStream()
            if (!bitmapToCompress.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                bitmapToCompress.recycle()
                throw IllegalStateException("Captured photo could not be compressed")
            }
            compressed = output.toByteArray()
            if (compressed.size <= maxBytes) break
            if (quality > 45) {
                quality -= 8
            } else if (attempts < 7) {
                val smaller = Bitmap.createScaledBitmap(
                    bitmapToCompress,
                    maxOf(1, (bitmapToCompress.width * 0.8f).roundToInt()),
                    maxOf(1, (bitmapToCompress.height * 0.8f).roundToInt()),
                    true,
                )
                if (smaller !== bitmapToCompress) bitmapToCompress.recycle()
                bitmapToCompress = smaller
                quality = 60
            } else {
                break
            }
            attempts += 1
        }
        val temporary = File(file.parentFile, "${file.name}.compressed")
        val finalBytes = compressed ?: throw IllegalStateException("Captured photo could not be compressed")
        FileOutputStream(temporary).use { output -> output.write(finalBytes) }
        capturedPhotoWidth = bitmapToCompress.width
        capturedPhotoHeight = bitmapToCompress.height
        bitmapToCompress.recycle()
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) throw IllegalStateException("Compressed photo could not be finalized")
        }
    }

    private fun orientBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }


    private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > maxDimension * 2) sample *= 2
        return sample
    }

    private fun startVideoRecording() {
        if (recording || finalized || !previewReady) return

        val capture = videoCapture ?: run {
            failCapture("The camera preview is no longer available")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failCapture("Microphone permission is no longer available")
            return
        }

        try {
            val file = createOutputFile(".mp4")
            outputFile = file
            lastRecordedBytes = 0L
            val outputOptions = FileOutputOptions.Builder(file)
                .setFileSizeLimit(maxBytes)
                .build()
            val pendingRecording = capture.output.prepareRecording(this, outputOptions)
                .withAudioEnabled()

            videoRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Status -> {
                        lastRecordedBytes = event.recordingStats.numBytesRecorded
                        captureDurationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                        if (recording && captureDurationMs >= maxDurationSeconds * 1_000L) {
                            stopCaptureForReview("max_duration")
                        }
                        if (recording && lastRecordedBytes >= maxBytes) {
                            stopCaptureForReview("max_bytes")
                        }
                    }

                    is VideoRecordEvent.Finalize -> handleVideoFinalized(event)
                }
            }
            startRecordingUi(recordedVideoWidth, recordedVideoHeight, recordedVideoFps)
            Log.i(TAG, "CameraX video recording started")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start CameraX video recording", error)
            releaseCaptureResources(deleteOutput = true)
            failCapture(error.message ?: "Unable to start video recording")
        }
    }

    private fun handleVideoFinalized(event: VideoRecordEvent.Finalize) {
        val file = outputFile
        videoRecording = null
        recording = false
        handler.removeCallbacks(complianceCheck)

        val fileSizeLimitReached = event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
        if (event.error != VideoRecordEvent.Finalize.ERROR_NONE && !fileSizeLimitReached) {
            Log.e(TAG, "CameraX video finalization failed: ${event.error} ${event.cause}")
            releaseCaptureResources(deleteOutput = true)
            failCapture(event.cause?.message ?: "CameraX could not finalize the video")
            return
        }

        if (fileSizeLimitReached) captureStopReason = "max_bytes"
        releaseCaptureResources(deleteOutput = false)
        showReview(file, "Aperçu prêt · vérifiez votre vidéo")
    }

    private fun startAudioRecording() {
        if (recording || finalized || mediaRecorder != null) return

        try {
            outputFile = createOutputFile(".m4a")
            mediaRecorder = newMediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outputFile!!.absolutePath)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44100)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setAudioChannels(1)
                prepare()
                start()
            }
            startRecordingUi(null, null, null)
            Log.i(TAG, "Audio recording started")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start audio capture", error)
            releaseCaptureResources(deleteOutput = true)
            failCapture(error.message ?: "Unable to start audio capture")
        }
    }

    @Suppress("DEPRECATION")
    private fun newMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

    private fun startRecordingUi(width: Int?, height: Int?, fps: Int?) {
        recording = true
        reviewReady = false
        stopRequested = false
        lastRecordedBytes = 0L
        startedAtMs = System.currentTimeMillis()
        actionButton.text = "■"
        actionButton.textSize = 26f
        actionButton.background = roundButtonBackground(recording = true)
        actionButton.contentDescription = "Arrêter l’enregistrement"
        actionButton.isEnabled = true
        statusText.text = if (width != null && height != null && fps != null) {
            "Enregistrement · ${width}x${height} · ${fps} fps"
        } else {
            "Enregistrement · AAC · 44,1 kHz · mono"
        }
        handler.post(complianceCheck)
    }

    private fun stopCaptureForReview(stopReason: String) {
        if (finalized || !recording) return

        captureStopReason = stopReason
        captureDurationMs = if (startedAtMs > 0L) {
            System.currentTimeMillis() - startedAtMs
        } else {
            0L
        }
        handler.removeCallbacks(complianceCheck)

        if (kind == "video") {
            val activeRecording = videoRecording ?: return
            if (stopRequested) return
            stopRequested = true
            actionButton.isEnabled = false
            statusText.text = "Finalisation de la vidéo…"
            activeRecording.stop()
            return
        }

        stopAudioCaptureForReview()
    }

    private fun stopAudioCaptureForReview() {
        val file = outputFile
        try {
            mediaRecorder?.stop()
        } catch (_: RuntimeException) {
            releaseCaptureResources(deleteOutput = true)
            failCapture("Capture produced an invalid media file")
            return
        }
        recording = false
        releaseCaptureResources(deleteOutput = false)
        showReview(file, "Aperçu prêt · vérifiez votre audio")
    }

    private fun showReview(file: File?, message: String) {
        if (file == null || !file.exists() || file.length() <= 0L) {
            failCapture("Capture produced an empty media file")
            return
        }

        reviewReady = true
        actionButton.visibility = View.GONE
        reviewControls.visibility = View.VISIBLE
        statusText.text = message
        Log.i(TAG, "Capture stopped and waiting for confirmation: ${file.length()} bytes")
    }

    private fun confirmCapture() {
        if (finalized || !reviewReady) return
        val file = outputFile
        if (file == null || !file.exists() || file.length() <= 0L) {
            failCapture("Capture produced an empty media file")
            return
        }

        val result = Intent().apply {
            putExtra("kind", kind)
            putExtra("fileName", file.name)
            putExtra("mimeType", when (kind) {
                "photo" -> "image/jpeg"
                "video" -> "video/mp4"
                else -> "audio/mp4"
            })
            putExtra("path", file.absolutePath)
            putExtra("size", file.length())
            putExtra("durationMs", mediaDurationMs(file, captureDurationMs))
            putExtra("sha256", sha256(file))
            putExtra("videoCodec", if (kind == "video") "h264" else "")
            putExtra("audioCodec", if (kind == "video" || kind == "audio") "aac" else "")
            putExtra(
                "bitrateBps",
                mediaBitrateBps(file) ?: if (kind == "video") profileFor(profileName).videoBitrate else if (kind == "audio") 64_000 else 0,
            )
            putExtra("stopReason", captureStopReason)
            putExtra("capture_id", intent.getStringExtra(EXTRA_RECOVERY_ID))
            putExtra("capture_scope_id", intent.getStringExtra(EXTRA_RECOVERY_SCOPE))
        }
        if (intent.getBooleanExtra(EXTRA_CORE_PIPELINE, false)) {
            val scopeId = intent.getStringExtra(EXTRA_RECOVERY_SCOPE) ?: error("Recovery scope is required")
            val captureId = intent.getStringExtra(EXTRA_RECOVERY_ID) ?: error("Capture id must be reserved before capture")
            val envelope = CaptureEnvelope(
                captureId = captureId,
                companyId = intent.getStringExtra(EXTRA_COMPANY_ID) ?: error("Company context is required"),
                userId = intent.getStringExtra(EXTRA_USER_ID) ?: error("User context is required"),
                deviceKeyId = intent.getStringExtra(EXTRA_DEVICE_KEY_ID) ?: error("Device key context is required"),
                challengeId = intent.getStringExtra(EXTRA_CHALLENGE_ID),
                challengeNonceSha256 = intent.getStringExtra(EXTRA_CHALLENGE_NONCE_SHA256),
                challengeMode = "online", captureMethod = "android_camerax",
                mediaSha256 = sha256(file), mediaSizeBytes = file.length(),
                mediaMime = result.getStringExtra("mimeType") ?: "image/jpeg",
                capturedAtDevice = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()),
                localSequence = intent.getLongExtra(EXTRA_LOCAL_SEQUENCE, 0L),
            )
            val canonical = envelope.canonicalJson()
            val signature = AndroidCaptureSigner.encodeBase64Url(AndroidCaptureSigner(this, scopeId).signCapture(envelope))
            CaptureJournalStore(File(filesDir, "capture-journal")).completeReserved(
                CaptureJournalStore.Pending(captureId, scopeId, envelope.mediaSha256, envelope.mediaSizeBytes, file.absolutePath, canonical, signature),
            )
            result.putExtra("capture_envelope_canonical_json", canonical)
            result.putExtra("signature_der_base64url", signature)
        }
        if (kind == "video") putVideoDimensions(result, recordedVideoWidth, recordedVideoHeight, recordedVideoFps)
        if (kind == "photo") {
            capturedPhotoWidth?.let { result.putExtra("width", it) }
            capturedPhotoHeight?.let { result.putExtra("height", it) }
            result.putExtra("durationMs", 0L)
            result.putExtra("compressionProfile", profileName)
        } else {
            result.putExtra("sampleRateHz", 44100)
            result.putExtra("channels", 1)
        }

        finalized = true
        setResult(RESULT_OK, result)
        finish()
    }

    private fun cancelCapture() {
        if (finalized) return
        finalized = true
        handler.removeCallbacks(complianceCheck)
        releaseCaptureResources(deleteOutput = true)
        discardReservationAfterExplicitCancellation()
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, "Capture cancelled"))
        finish()
    }

    private fun putVideoDimensions(result: Intent, width: Int?, height: Int?, fps: Int?) {
        width?.let { result.putExtra("width", it) }
        height?.let { result.putExtra("height", it) }
        fps?.let { result.putExtra("fps", it) }
    }

    private fun failCapture(message: String) {
        if (finalized) return
        Log.e(TAG, "Capture failed: $message")
        finalized = true
        handler.removeCallbacks(complianceCheck)
        releaseCaptureResources(deleteOutput = true)
        discardReservationAfterExplicitCancellation()
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, message))
        finish()
    }

    private fun discardReservationAfterExplicitCancellation() {
        if (!intent.getBooleanExtra(EXTRA_CORE_PIPELINE, false)) return
        val captureId = intent.getStringExtra(EXTRA_RECOVERY_ID) ?: return
        val scopeId = intent.getStringExtra(EXTRA_RECOVERY_SCOPE) ?: return
        val expectedMedia = File(filesDir, "proovit-captures/proovit_${kind}_$captureId${if (kind == "photo") ".jpg" else if (kind == "video") ".mp4" else ".m4a"}")
        runCatching { CaptureJournalStore(File(filesDir, "capture-journal")).cancelReservation(captureId, scopeId, expectedMedia) }
    }

    private fun releaseCaptureResources(deleteOutput: Boolean) {
        try {
            videoRecording?.stop()
        } catch (_: Exception) {
            // no-op
        }
        videoRecording = null
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
            // no-op
        }
        cameraProvider = null
        videoCapture = null
        imageCapture = null
        previewReady = false

        try {
            mediaRecorder?.reset()
        } catch (_: Exception) {
            // no-op
        }
        mediaRecorder?.release()
        mediaRecorder = null
        if (deleteOutput) outputFile?.delete()
    }

    private fun createOutputFile(extension: String): File {
        val directory = File(filesDir, "proovit-captures")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create durable native capture storage")
        }
        val captureId = intent.getStringExtra(EXTRA_RECOVERY_ID)
        val outputName = if (intent.getBooleanExtra(EXTRA_CORE_PIPELINE, false) && !captureId.isNullOrBlank()) "proovit_${kind}_$captureId$extension" else "proovit_${kind}_${UUID.randomUUID()}$extension"
        val output = File(directory, outputName)
        if (!output.createNewFile()) {
            throw IllegalStateException("Unable to reserve durable native capture file")
        }
        return output
    }

    private fun profileFor(name: String): VideoProfile = when (name) {
        "compact" -> VideoProfile(listOf(Quality.HD, Quality.SD), 1280, 720, 24, 1_000_000, 1920, 82)
        "high" -> VideoProfile(listOf(Quality.FHD, Quality.HD, Quality.SD), 1920, 1080, 30, 2_800_000, 3840, 92)
        else -> VideoProfile(listOf(Quality.HD, Quality.SD), 1280, 720, 24, 1_200_000, 2560, 88)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun mediaDurationMs(file: File, fallback: Long): Long {
        return readMediaMetadata(file, MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: fallback
    }

    private fun mediaBitrateBps(file: File): Long? {
        return readMediaMetadata(file, MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
    }

    private fun readMediaMetadata(file: File, key: Int): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(key)
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(complianceCheck)
        if (!finalized) releaseCaptureResources(deleteOutput = true)
        super.onDestroy()
    }
}
