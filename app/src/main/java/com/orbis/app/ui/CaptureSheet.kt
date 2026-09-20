package com.orbis.app.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.orbis.app.deed.DeedPhotoCapture
import kotlinx.coroutines.launch

/**
 * Camera capture for a good deed, which is one of the earn actions on
 * [EarnScreen].
 *
 * Lives in its own file because it outlived the screen it was written for: the
 * good-deed log became an action in the earn-back loop rather than a feature of
 * its own.
 */
@Composable
internal fun CaptureSheet(
    onCancel: (String?) -> Unit,
    onSave: (String?, String) -> Unit,
    saving: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val imageCapture = remember { ImageCapture.Builder().build() }
    var photoPath by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = if (photoPath == null) "Capture your good deed" else "Add a note",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        if (photoPath == null) {
            AndroidView(
                // Takes whatever is left rather than a fixed 380dp: in landscape,
                // or on a short screen, that height pushed the shutter button off.
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { viewContext ->
                    PreviewView(viewContext).also { previewView ->
                        scope.launch {
                            val provider = DeedPhotoCapture.awaitProvider(viewContext)
                            val preview = CameraPreview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            runCatching {
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageCapture,
                                )
                            }
                        }
                    }
                },
            )
        } else {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("What did you do?") },
                singleLine = false,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onCancel(photoPath) }, enabled = !saving) {
                Text("Cancel")
            }

            if (photoPath == null) {
                Button(onClick = {
                    scope.launch {
                        photoPath = DeedPhotoCapture.capture(context, imageCapture)?.absolutePath
                    }
                }) {
                    Text("Take photo")
                }
            } else {
                Button(onClick = { onSave(photoPath, note) }, enabled = !saving) {
                    Text(if (saving) "Saving…" else "Save")
                }
            }
        }

        // Photos are optional: the point is the deed, not the evidence.
        if (photoPath == null) {
            OutlinedButton(onClick = { onSave(null, note) }, enabled = !saving) {
                Text("Log without a photo")
            }
        }
    }
}
