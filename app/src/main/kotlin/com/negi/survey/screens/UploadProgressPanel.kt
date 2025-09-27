package com.negi.survey.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import com.negi.survey.vm.UploadItemUi
import com.negi.survey.vm.UploadQueueViewModel

@Composable
fun UploadProgressOverlay(
    vm: UploadQueueViewModel = viewModel(
        factory = UploadQueueViewModel.factory(
            LocalContext.current.applicationContext as Application
        )
    )
) {
    val items by vm.itemsFlow.collectAsState(initial = emptyList())
    val visible = items.any {
        it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.BLOCKED
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                shape = MaterialTheme.shapes.large
            ) {
                Column(Modifier.widthIn(max = 520.dp).padding(16.dp)) {
                    Text(
                        "Background uploads",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items) { item ->
                            UploadRow(item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadRow(u: UploadItemUi) {
    val ctx = LocalContext.current
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                u.fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(u.message.orEmpty(), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(6.dp))
        when (u.state) {
            WorkInfo.State.RUNNING -> {
                LinearProgressIndicator(
                    progress = { (u.percent ?: 0) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                if (u.percent != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("${u.percent}%", style = MaterialTheme.typography.labelSmall)
                }
            }
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            WorkInfo.State.SUCCEEDED -> {
                LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
                if (!u.fileUrl.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = {
                            val i = Intent(Intent.ACTION_VIEW, Uri.parse(u.fileUrl))
                            ctx.startActivity(i)
                        }
                    ) { Text("Open on GitHub") }
                }
            }
            else -> {
                // FAILED / CANCELLED
                LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
