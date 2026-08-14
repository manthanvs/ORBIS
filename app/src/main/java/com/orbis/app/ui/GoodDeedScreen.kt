package com.orbis.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.orbis.app.data.GoodDeedEntry
import com.orbis.app.deed.DeedPhotoCapture
import com.orbis.app.ui.theme.OrbisTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIMESTAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())

@Composable
fun GoodDeedScreen(
    state: GoodDeedUiState,
    onStartCapture: () -> Unit,
    onCancelCapture: (String?) -> Unit,
    onSave: (String?, String) -> Unit,
    onSendTestPrompt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // POST_NOTIFICATIONS is denied by default on Android 13+. Declaring it in the
    // manifest is not enough: without asking, GoodDeedWorker's permission check
    // fails and the prompt silently never appears.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Declining is fine - the in-app tab still works. */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var hasCamera by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCamera = granted
        if (granted) onStartCapture()
    }

    if (state.capturing && hasCamera) {
        CaptureSheet(onCancel = onCancelCapture, onSave = onSave, saving = state.saving)
        return
    }

    // A LazyColumn rather than a scrolling Column: the log has no upper bound, so
    // composing every past deed to show the most recent handful gets slower for
    // as long as the user keeps using the feature.
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
            Text(
                text = "Good deeds",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        item(key = "streak") {
            StreakCard(
                streak = state.streak,
                doneToday = state.doneToday,
                onStart = {
                    if (hasCamera) {
                        onStartCapture()
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
            )
        }

        state.message?.let { message ->
            item(key = "message") {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
            }
        }

        item(key = "test-prompt") {
            // The real prompt is daily, so without this the loop cannot be checked
            // without waiting a day.
            OutlinedButton(onClick = onSendTestPrompt) {
                Text("Send a test prompt now")
            }
        }

        if (state.entries.isNotEmpty()) {
            item(key = "log-title") {
                Text(
                    text = "Your log",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Keyed on the row id so inserting a deed does not re-compose the
            // rows below it.
            items(state.entries, key = { it.id }) { entry ->
                Card {
                    DeedRow(entry)
                }
            }
        }
    }
}

@Composable
private fun StreakCard(streak: Int, doneToday: Boolean, onStart: () -> Unit) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when {
                    streak <= 0 -> "Start a streak"
                    streak == 1 -> "1 day"
                    else -> "$streak days"
                },
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (doneToday) {
                    "Today's good deed is logged. That's the one that counts."
                } else {
                    "Do one small kind thing today, then log it here."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onStart) {
                Text(if (doneToday) "Log another" else "Log a good deed")
            }
        }
    }
}

@Composable
private fun DeedRow(entry: GoodDeedEntry) {
    val when_ = Instant.ofEpochMilli(entry.timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(TIMESTAMP)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.padding(end = 12.dp)) {
            Text(
                text = entry.note.ifBlank { "Logged" },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = when_,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.photoPath != null) {
            Text(
                text = "photo",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CaptureSheet(
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp),
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

@Preview(showBackground = true)
@Composable
private fun GoodDeedPreview() {
    OrbisTheme {
        GoodDeedScreen(
            state = GoodDeedUiState(
                streak = 3,
                doneToday = true,
                entries = listOf(
                    GoodDeedEntry(1, System.currentTimeMillis(), null, "Helped a neighbour", true),
                ),
            ),
            onStartCapture = {},
            onCancelCapture = {},
            onSave = { _, _ -> },
            onSendTestPrompt = {},
        )
    }
}
