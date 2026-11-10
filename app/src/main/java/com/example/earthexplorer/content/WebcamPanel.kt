package com.example.earthexplorer.content

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.earthexplorer.data.WebcamCamera
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Camera media shown inside the details window; video never replaces the native city terrain. */
@Composable
internal fun ActiveWebcamMedia(camera: WebcamCamera) {
    var videoFailure by remember(camera.id) { mutableStateOf<String?>(null) }
    var videoAttempt by remember(camera.id) { mutableStateOf(0) }
    var mediaState by remember(camera.id, videoAttempt) { mutableStateOf(WebcamMediaState()) }
    var clockMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            clockMillis = System.currentTimeMillis()
            delay(10_000)
        }
    }
    val showingVideo = camera.videoUrl != null && videoFailure == null
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val frameShape = RoundedCornerShape(18.dp)
        Box(Modifier.weight(1f).fillMaxWidth().clip(frameShape).background(Color.Black)
            .border(1.dp, ExplorerPalette.line, frameShape), contentAlignment = Alignment.Center) {
            if (showingVideo) {
                key(videoAttempt) {
                    NativeWebcamVideo(camera.videoUrl!!, onState = { mediaState = it },
                        onFailure = { videoFailure = it })
                }
            } else WebcamSnapshot(camera.imageUrl, onState = { mediaState = it })
            if (mediaState.loading) StatusLine("Loading camera…", ExplorerPalette.webcam)
        }
        StatusLine(if (showingVideo) "Latest traffic clip · replaying · checks every minute"
            else "Traffic camera image · refreshes every minute", ExplorerPalette.webcam)
        Footnote(webcamFreshnessText(mediaState.sourceUpdatedAtMillis, mediaState.checkedAtMillis, clockMillis))
        val error = mediaState.error ?: videoFailure
        if (error != null) Text(error, style = PicoTheme.typography.bodySmall, color = ExplorerPalette.danger)
        if (camera.videoUrl != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Footnote("Clips are periodically recorded, not continuous live video.", Modifier.weight(1f))
                HudButton("Reload clip", { videoFailure = null; videoAttempt++ }, accent = ExplorerPalette.webcam)
            }
        }
    }
}

@Composable
private fun WebcamSnapshot(url: String, onState: (WebcamMediaState) -> Unit) {
    var frame by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        var state = WebcamMediaState()
        onState(state)
        while (isActive) {
            try {
                val snapshot = withContext(Dispatchers.IO) { fetchWebcamSnapshot(url) }
                frame = snapshot.bitmap.asImageBitmap()
                state = WebcamMediaState(loading = false, sourceUpdatedAtMillis = snapshot.sourceUpdatedAtMillis,
                    checkedAtMillis = System.currentTimeMillis())
            } catch (_: IOException) {
                state = state.copy(loading = false, error = if (frame == null)
                    "Camera unavailable. The provider may be offline; checking again in one minute."
                    else "Refresh failed. The last received image is still displayed.")
            }
            onState(state)
            delay(WEBCAM_REFRESH_MILLIS)
        }
    }
    frame?.let {
        Image(it, contentDescription = "Latest public camera image", modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit)
    }
}

internal fun webcamFreshnessText(sourceUpdatedAtMillis: Long?, checkedAtMillis: Long?, nowMillis: Long): String {
    if (checkedAtMillis == null) return "Waiting for the provider."
    val format = DateTimeFormatter.ofPattern("HH:mm:ss z").withZone(ZoneId.systemDefault())
    val checked = "Checked ${format.format(Instant.ofEpochMilli(checkedAtMillis))}"
    if (sourceUpdatedAtMillis == null) return "$checked · Provider capture time unavailable"
    val ageMinutes = ((nowMillis - sourceUpdatedAtMillis).coerceAtLeast(0) / 60_000)
    val age = if (ageMinutes < 1) "less than a minute ago" else "$ageMinutes min ago"
    val warning = if (ageMinutes >= 15) " · May be stale" else ""
    return "$checked · Provider file updated $age$warning"
}
