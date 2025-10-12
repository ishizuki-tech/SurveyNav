// file: app/src/main/java/com/negi/survey/screens/UploadProgressOverlay.kt
package com.negi.survey.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import com.negi.survey.vm.UploadItemUi
import com.negi.survey.vm.UploadQueueViewModel
import kotlin.math.roundToInt

@Composable
fun UploadProgressOverlay(
    vm: UploadQueueViewModel = viewModel(
        factory = UploadQueueViewModel.factory(
            LocalContext.current.applicationContext as Application
        )
    ),
    showScrim: Boolean = false
) {
    val items by vm.itemsFlow.collectAsState(initial = emptyList())

    // Visible when any item is running/queued/blocked (derived to avoid churn).
    val visible by derivedStateOf {
        items.any {
            it.state == WorkInfo.State.RUNNING ||
                    it.state == WorkInfo.State.ENQUEUED ||
                    it.state == WorkInfo.State.BLOCKED
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Optional scrim to focus attention to the overlay.
        AnimatedVisibility(visible = showScrim && visible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            )
        }

        AnimatedVisibility(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            // Gradient rim behind the card for a “neon glass” vibe.
            val rimShape = RoundedCornerShape(20.dp)
            val a = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            val b = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)

            Box(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .drawBehind {
                        val stroke = 8f
                        drawRoundRect(
                            brush = Brush.linearGradient(listOf(a, b)),
                            size = size,
                            cornerRadius = CornerRadius(24f, 24f),
                            style = Stroke(width = stroke)
                        )
                    }
                    .padding(4.dp)
            ) {
                ElevatedCard(
                    shape = rimShape,
                    colors = CardDefaults.elevatedCardColors(
                        // Slight tint to pop up from the scrim/background
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 10.dp)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            "Background uploads",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.semantics { heading() }
                        )
                        Spacer(Modifier.height(8.dp))

                        LazyColumn(
                            modifier = Modifier.heightIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Use stable keys (prefer id; fallback to fileName)
                            items(items, key = { it.id ?: it.fileName }) { item ->
                                UploadRowFancy(item)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadRowFancy(u: UploadItemUi) {
    val ctx = LocalContext.current

    // Map state → accent color, icon, label.
    // IMPORTANT: Don't call composables inside remember{}; just compute directly.
    val (accent, icon, label) = styleFor(u.state)

    // Progress target value per state (null → indeterminate)
    val target = u.percent?.coerceIn(0, 100)?.div(100f) ?: when (u.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> null
        WorkInfo.State.RUNNING -> 0f
        WorkInfo.State.SUCCEEDED -> 1f
        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> 0f
    }
    val animProgress by animateFloatAsState(
        targetValue = target ?: 0f,
        label = "upload_progress"
    )

    // Soft vertical gradient background strip per row
    val rowGradient = Brush.verticalGradient(
        colors = listOf(
            accent.copy(alpha = 0.10f),
            Color.Transparent
        )
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowGradient, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        // Header: filename + status pill
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = accent)
                Spacer(Modifier.width(8.dp))
                Text(
                    u.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            AssistChip(
                onClick = { /* decorative */ },
                label = { Text(label) },
                leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = accent.copy(alpha = 0.15f),
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    leadingIconContentColor = accent
                )
            )
        }

        Spacer(Modifier.height(6.dp))

        // Optional message line
        u.message?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
        }

        // Progress area per state (handles error/success gracefully)
        when (u.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = accent
                )
            }
            WorkInfo.State.RUNNING -> {
                LinearProgressIndicator(
                    progress = { animProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = accent
                )
                if (u.percent != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(animProgress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxWidth(),
                    color = accent
                )
                // Deep link to the uploaded artifact if available
                if (!u.fileUrl.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = {
                            val i = Intent(Intent.ACTION_VIEW, Uri.parse(u.fileUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { ctx.startActivity(i) }
                        }
                    ) { Text("Open on GitHub") }
                }
            }
            WorkInfo.State.FAILED -> {
                // Error track with clear status color; keep layout consistent
                LinearProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Upload failed.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                // Optional: offer "details" or "retry" hooks here if ViewModel supports it
            }
            WorkInfo.State.CANCELLED -> {
                LinearProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Upload cancelled.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/* -------------------------- Style helpers -------------------------- */

/** Map WorkInfo.State to an accent color, icon and short label. */
@Composable
private fun styleFor(state: WorkInfo.State): Triple<Color, ImageVector, String> {
    val c = MaterialTheme.colorScheme
    return when (state) {
        WorkInfo.State.RUNNING   -> Triple(c.primary,        Icons.Outlined.CloudUpload, "Uploading")
        WorkInfo.State.ENQUEUED  -> Triple(c.secondary,      Icons.Outlined.CloudQueue,  "Queued")
        WorkInfo.State.BLOCKED   -> Triple(c.tertiary,       Icons.Outlined.Block,       "Blocked")
        WorkInfo.State.SUCCEEDED -> Triple(c.inversePrimary, Icons.Outlined.CloudDone,   "Done")
        WorkInfo.State.FAILED    -> Triple(c.error,          Icons.Outlined.ErrorOutline,"Failed")
        WorkInfo.State.CANCELLED -> Triple(c.error,          Icons.Outlined.ErrorOutline,"Cancelled")
    }
}
