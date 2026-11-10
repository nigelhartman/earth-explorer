package com.example.earthexplorer.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.earthexplorer.data.FeatureLayer
import com.example.earthexplorer.data.FeatureSelection
import com.example.earthexplorer.data.GeoFrame
import com.example.earthexplorer.data.GlobeFeature
import com.example.earthexplorer.data.LayerFeed
import com.example.earthexplorer.data.LocationStore
import com.example.earthexplorer.data.SolarPosition
import com.example.earthexplorer.data.WebcamLayerStore
import com.example.earthexplorer.data.toFeature
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** Event coordinates stay fixed while the independent terrain-center coordinates follow dragging. */
@Composable
fun FeatureDetailsPanel(feature: GlobeFeature, feed: LayerFeed?, presentInCurrentFeed: Boolean) {
    val origin by LocationStore.selected.collectAsState()
    val location by LocationStore.viewLocation.collectAsState()
    val center by LocationStore.cityCenter.collectAsState()
    val renderState by LocationStore.renderState.collectAsState()
    val now = liveClockMillis()
    val displayed = if (feature.layer == FeatureLayer.SUN) {
        remember(now / 30_000) { SolarPosition.at(now).toFeature() }
    } else feature
    val isQuake = feature.layer == FeatureLayer.EARTHQUAKES
    val isCatalogQuake = isQuake && feature.id != com.example.earthexplorer.data.AssistantDemo.SCENARIO_ID
    val isIss = feature.layer == FeatureLayer.POSITIONS && feature.highlighted
    val accent = ExplorerPalette.layer(feature.layer)
    val layerName = when (feature.layer) {
        FeatureLayer.AIRCRAFT -> "Aircraft"
        FeatureLayer.EARTHQUAKES -> "Earthquake"
        FeatureLayer.POSITIONS -> if (isIss) "Space station" else "Satellite"
        FeatureLayer.SUN -> "Sun & terminator"
        FeatureLayer.WEBCAMS -> "Webcam"
    }

    ExplorerWindow {
        HudHeader(layerName, displayed.title, accent, trailing = { UtcClock() })

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (feature.layer == FeatureLayer.WEBCAMS) {
                // The live feed lives here, beside the 3D city, instead of in a separate window.
                val camera = WebcamLayerStore.find(feature.id)
                if (camera != null) key(camera.id) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) { ActiveWebcamMedia(camera) }
                } else Footnote("This camera is no longer in the catalog.")
            }
            if (displayed.subtitle.isNotBlank()) Text(displayed.subtitle,
                style = PicoTheme.typography.bodyLarge, color = ExplorerPalette.textSecondary)

            if (!presentInCurrentFeed) {
                Footnote("This observation is no longer in the current feed. These are its last known details, not a live position.")
            }
            feed?.error?.let { Text(it, style = PicoTheme.typography.bodySmall, color = ExplorerPalette.danger) }
            if (feed?.loading == true) StatusLine("Updating source information…", accent)

            val tiles = displayed.details.take(3)
            val remaining = displayed.details.drop(3)
            if (tiles.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                tiles.forEach { detail -> StatTile(detail.label, detail.value, Modifier.weight(1f), accent, monospace = false) }
            }

            GlowCard(accent = accent) {
                Eyebrow(when (feature.layer) {
                    FeatureLayer.EARTHQUAKES -> "Exact epicenter"
                    FeatureLayer.AIRCRAFT -> "Reported position"
                    FeatureLayer.POSITIONS -> if (isIss) "Latest reported position" else "Predicted ground position"
                    FeatureLayer.SUN -> "Sun directly overhead"
                    FeatureLayer.WEBCAMS -> "Published camera location"
                }, accent)
                Text(coordinateText(displayed.latitude, displayed.longitude),
                    style = mono(PicoTheme.typography.bodyLarge), color = ExplorerPalette.textPrimary)
                Text(when {
                    isQuake -> "Event time · ${utcTimestamp(displayed.observedAtMillis)}"
                    feature.layer == FeatureLayer.WEBCAMS -> "Catalog checked ${utcTimestamp(displayed.observedAtMillis)} · not the frame capture time"
                    feature.layer == FeatureLayer.SUN -> "Calculated for ${utcTimestamp(displayed.observedAtMillis)}"
                    feature.layer == FeatureLayer.POSITIONS && !isIss ->
                        "Prediction time · ${utcTimestamp(displayed.observedAtMillis)} · approximate, not telemetry"
                    else -> "Observed ${freshnessText(displayed.observedAtMillis, now)} · ${utcTimestamp(displayed.observedAtMillis)}"
                }, style = PicoTheme.typography.bodySmall, color = ExplorerPalette.textSecondary)
            }

            if (remaining.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                remaining.forEach { detail -> InfoRow(detail.label, detail.value) }
            }
            if (feature.layer == FeatureLayer.WEBCAMS) {
                Footnote("The teal beacon in the 3D city marks the camera's published position; its view cone is an estimate, not provider telemetry. Video and tiles are separate data sources.")
            }
            if (feature.layer == FeatureLayer.SUN) {
                Footnote("The line separates geometric daylight from night. Twilight and atmospheric refraction are not included.")
            }
            if (isCatalogQuake) {
                Footnote("The 3D view opens the exact epicenter. Epicenters can lie offshore or outside cities, so valid terrain may show ocean or open land rather than buildings.")
                Footnote("Historical USGS observations. This is not an earthquake or tsunami warning service.")
            }

            origin?.let { anchor ->
                val coordinate = GeoFrame(anchor.latitude, anchor.longitude).coordinate(center)
                GlowCard {
                    Eyebrow("Center of 3D view", ExplorerPalette.cyan)
                    location?.city?.let { city ->
                        Text(listOfNotNull(city, location?.country).joinToString(" · "),
                            style = PicoTheme.typography.titleMedium, color = ExplorerPalette.textPrimary)
                    }
                    Text(coordinateText(coordinate.first, coordinate.second),
                        style = mono(PicoTheme.typography.bodyLarge), color = ExplorerPalette.textPrimary)
                    Footnote(if (isQuake) "Drag to explore around the epicenter. The event coordinates above stay fixed."
                        else if (feature.layer == FeatureLayer.WEBCAMS) "Drag to explore around the camera. The feed remains at its original location."
                        else "Drag to explore the terrain. This view does not follow the moving object automatically.")
                    when {
                        renderState.error != null -> Text(
                            if (isQuake) "Detailed 3D coverage may be unavailable at this epicenter, especially offshore. The earthquake information and exact coordinates are still available."
                            else "Detailed 3D coverage may be unavailable here. Your selected position is still shown above.",
                            style = PicoTheme.typography.bodySmall, color = ExplorerPalette.danger)
                        renderState.loadedTiles == 0 -> StatusLine("Loading 3D terrain at this location…")
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Footnote("Source · ${displayed.source}")
                displayed.sourceUrl?.let { Footnote(it) }
                feed?.note?.takeIf { it.isNotBlank() }?.let { Footnote(it) }
            }
        }

        HudDivider()
        if (origin == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (feature.layer != FeatureLayer.SUN) {
                    HudButton("Dive in", { FeatureSelection.exploreGround(displayed) }, Modifier.weight(1f), accent, filled = true)
                }
                HudButton("Back", FeatureSelection::clear, Modifier.weight(1f))
            }
        } else Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Footnote(renderState.attribution.ifBlank { "Google Maps · Cesium ion" })
            Footnote("Cesium ion · Upgrade for commercial use")
        }
    }
}

/** UI age labels advance without making any network requests. */
@Composable
internal fun liveClockMillis(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

internal fun latitudeText(latitude: Double): String =
    "%.5f° %s".format(Locale.US, kotlin.math.abs(latitude), if (latitude >= 0) "N" else "S")

internal fun longitudeText(longitude: Double): String =
    "%.5f° %s".format(Locale.US, kotlin.math.abs(longitude), if (longitude >= 0) "E" else "W")

internal fun coordinateText(latitude: Double, longitude: Double): String =
    "${latitudeText(latitude)} · ${longitudeText(longitude)}"

internal fun freshnessText(observedAtMillis: Long, now: Long): String {
    val seconds = ((now - observedAtMillis) / 1000).coerceAtLeast(0)
    return when {
        seconds < 5 -> "just now"
        seconds < 60 -> "$seconds s ago"
        seconds < 3_600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3_600} h ago"
        else -> "${seconds / 86_400} days ago"
    }
}

private val FEATURE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss 'UTC'", Locale.ENGLISH)
    .withZone(ZoneOffset.UTC)

internal fun utcTimestamp(timeMillis: Long): String = FEATURE_TIME_FORMAT.format(Instant.ofEpochMilli(timeMillis))
