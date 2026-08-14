package com.orbis.app.deed

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Saves a good-deed photo into app-private storage.
 *
 * `filesDir` deliberately, never shared storage: it keeps the whole feature clear
 * of storage permissions and keeps the user's photos out of the gallery.
 */
object DeedPhotoCapture {

    private const val DIRECTORY = "good_deeds"

    /**
     * Hoisted, and a `DateTimeFormatter` rather than a `SimpleDateFormat`: the
     * latter was rebuilt on every call and is not thread-safe, so it could not
     * have been hoisted as it stood.
     */
    private val FILE_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)

    fun photoDirectory(context: Context): File =
        File(context.filesDir, DIRECTORY).apply { mkdirs() }

    fun newPhotoFile(context: Context): File {
        val stamp = FILE_STAMP.format(Instant.now().atZone(ZoneId.systemDefault()))
        return File(photoDirectory(context), "deed-$stamp.jpg")
    }

    suspend fun awaitProvider(context: Context): ProcessCameraProvider =
        suspendCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { continuation.resume(future.get()) },
                ContextCompat.getMainExecutor(context),
            )
        }

    /** @return the saved file, or null if the capture failed. */
    suspend fun capture(context: Context, imageCapture: ImageCapture): File? {
        val target = newPhotoFile(context)
        val options = ImageCapture.OutputFileOptions.Builder(target).build()

        return suspendCoroutine { continuation ->
            imageCapture.takePicture(
                options,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        continuation.resume(target)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        // Leave nothing behind on failure.
                        runCatching { target.delete() }
                        continuation.resume(null)
                    }
                },
            )
        }
    }
}
