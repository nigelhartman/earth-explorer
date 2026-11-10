package com.example.earthexplorer.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.earthexplorer.data.AircraftLayerStore
import com.example.earthexplorer.data.EarthquakeLayerStore
import com.example.earthexplorer.data.FeatureLayer
import com.example.earthexplorer.data.FeatureSelection
import com.example.earthexplorer.data.GlobeFeature
import com.example.earthexplorer.data.GlobeFocus
import com.example.earthexplorer.data.GlobeSelection
import com.example.earthexplorer.data.LayerFeed
import com.example.earthexplorer.data.MapLayers
import com.example.earthexplorer.data.PositionsLayerStore
import com.example.earthexplorer.data.WebcamLayerStore
import com.example.earthexplorer.data.SolarPosition
import com.example.earthexplorer.data.toFeature
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Feed status and selectable observations complement tapping the native globe markers. */
@Composable
fun LayerOverview() {
    val aircraftVisible by MapLayers.aircraftVisible.collectAsState()
    val earthquakesVisible by MapLayers.earthquakesVisible.collectAsState()
    val sunVisible by MapLayers.sunVisible.collectAsState()
    val positionsVisible by MapLayers.positionsVisible.collectAsState()
    val webcamsVisible by MapLayers.webcamsVisible.collectAsState()
    val webcams by WebcamLayerStore.state.collectAsState()
    val aircraft by AircraftLayerStore.state.collectAsState()
    val earthquakes by EarthquakeLayerStore.state.collectAsState()
    val positions by PositionsLayerStore.state.collectAsState()
    val focus by GlobeSelection.focus.collectAsState()
    val now = liveClockMillis()
    val nearbyAircraft = remember(aircraft.features, (focus.latitude / 5).roundToInt(), (focus.longitude / 5).roundToInt()) {
        nearest(aircraft.features, focus)
    }
    val nearbySatellites = remember(positions.features, (focus.latitude / 5).roundToInt(), (focus.longitude / 5).roundToInt()) {
        nearest(positions.features.filterNot { it.highlighted }, focus)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!aircraftVisible && !earthquakesVisible && !sunVisible && !positionsVisible && !webcamsVisible) {
            Eyebrow("No live layer online", ExplorerPalette.textSecondary)
            Text("Choose a layer", style = PicoTheme.typography.headlineMedium, color = ExplorerPalette.textPrimary)
            Text("Turn on a live layer or Webcams in the Map layers window on the left.",
                style = PicoTheme.typography.bodyLarge, color = ExplorerPalette.textSecondary)
            Footnote("Then tap a marker on Earth or choose an observation here to learn more.")
        }

        if (webcamsVisible) GlowCard(accent = ExplorerPalette.webcam) {
            Eyebrow("Webcams · public traffic cameras", ExplorerPalette.webcam)
            FeedStatus(webcams, now, "Catalog checked")
            Footnote("Choose a camera to dive into its location as 3D tiles and play its feed here. London provides updated video clips; Austin provides refreshed stills.")
            HudButton("Refresh camera catalog", WebcamLayerStore::refresh, accent = ExplorerPalette.webcam, enabled = !webcams.loading)
            webcams.features.forEach { feature -> FeatureCard(feature.title, feature.subtitle, ExplorerPalette.webcam, { open(feature) }) }
        }

        if (aircraftVisible) GlowCard(accent = ExplorerPalette.aircraft) {
            Eyebrow("Airplanes · OpenSky", ExplorerPalette.aircraft)
            FeedStatus(aircraft, now, "Snapshot")
            HudButton("Refresh flights · 4 credits", AircraftLayerStore::refresh, accent = ExplorerPalette.aircraft, enabled = !aircraft.loading)
            if (nearbyAircraft.isNotEmpty()) {
                Footnote("Nearest the center of your globe view")
                nearbyAircraft.forEach { feature ->
                    FeatureCard(feature.title, feature.subtitle.ifBlank { "OpenSky observation" }, ExplorerPalette.aircraft, { open(feature) })
                }
            }
        }

        if (earthquakesVisible) GlowCard(accent = ExplorerPalette.quake) {
            Eyebrow("Earthquakes · Ring of Fire", ExplorerPalette.quake)
            FeedStatus(earthquakes, now, "Catalog updated")
            Footnote("Select an epicenter to open its exact GPS location in the 3D terrain window. Offshore locations may have no detailed mesh coverage.")
            HudButton("Refresh earthquakes", EarthquakeLayerStore::refresh, accent = ExplorerPalette.quake, enabled = !earthquakes.loading)
            if (earthquakes.features.isNotEmpty()) {
                Eyebrow("Latest events", ExplorerPalette.textSecondary)
                earthquakes.features.take(8).forEach { feature ->
                    FeatureCard(feature.title, utcTimestamp(feature.observedAtMillis), ExplorerPalette.quake, { open(feature) })
                }
            }
        }

        if (sunVisible) GlowCard(accent = ExplorerPalette.sun) {
            val solar = remember(now / 30_000) { SolarPosition.at(now) }
            Eyebrow("Sun & terminator", ExplorerPalette.sun)
            Text("Live daylight boundary", style = PicoTheme.typography.titleMedium, color = ExplorerPalette.textPrimary)
            Text(coordinateText(solar.latitude, solar.longitude), style = mono(PicoTheme.typography.bodyLarge),
                color = ExplorerPalette.textPrimary)
            Footnote("Sun directly overhead · calculated locally for ${utcTimestamp(solar.epochMillis)}. The terminator separates geometric day and night; twilight is not shown.")
            HudButton("Focus sunlit center", {
                GlobeSelection.focusOn(solar.latitude, solar.longitude)
                FeatureSelection.select(solar.toFeature())
            }, accent = ExplorerPalette.sun)
            Footnote("NOAA solar equations · no network requests")
        }

        if (positionsVisible) GlowCard(accent = ExplorerPalette.satellite) {
            Eyebrow("Positions · ISS & satellites", ExplorerPalette.satellite)
            FeedStatus(positions, now, "Positions updated")
            val iss = positions.features.firstOrNull { it.highlighted }
            if (iss != null) {
                Text("ISS · highlighted on Earth", style = PicoTheme.typography.titleMedium, color = ExplorerPalette.gold)
                Text(coordinateText(iss.latitude, iss.longitude), style = mono(PicoTheme.typography.bodyLarge),
                    color = ExplorerPalette.textPrimary)
                Footnote("Reported ${freshnessText(iss.observedAtMillis, now)} · ${utcTimestamp(iss.observedAtMillis)}")
                HudButton("Focus ISS", {
                    GlobeSelection.focusOn(iss.latitude, iss.longitude)
                    FeatureSelection.select(iss)
                }, Modifier.fillMaxWidth(), accent = ExplorerPalette.gold, filled = true)
            } else Footnote(if (positions.loading) "Waiting for the ISS position…" else "A fresh ISS position is currently unavailable.")
            HudButton("Refresh ISS", PositionsLayerStore::refresh, accent = ExplorerPalette.satellite)
            if (nearbySatellites.isNotEmpty()) {
                Eyebrow("GPS satellites nearest your view", ExplorerPalette.textSecondary)
                Footnote("Approximate orbital predictions, not live telemetry.")
                nearbySatellites.forEach { feature ->
                    FeatureCard(feature.title, "Predicted orbit · ${feature.subtitle}", ExplorerPalette.satellite, { open(feature) })
                }
            }
        }
    }
}

@Composable
private fun FeedStatus(feed: LayerFeed, now: Long, updatedLabel: String) {
    if (feed.loading) StatusLine("Updating…")
    feed.error?.let { Text(it, style = PicoTheme.typography.bodySmall, color = ExplorerPalette.danger) }
    if (feed.note.isNotBlank()) Footnote(feed.note)
    feed.updatedAtMillis?.let {
        Footnote("$updatedLabel ${freshnessText(it, now)} · ${utcTimestamp(it)}")
    }
    if (!feed.loading && feed.error == null && feed.features.isEmpty()) {
        Footnote("No observations available in this update.")
    }
}

private fun open(feature: GlobeFeature) {
    if (feature.layer != FeatureLayer.EARTHQUAKES && feature.layer != FeatureLayer.WEBCAMS)
        GlobeSelection.focusOn(feature.latitude, feature.longitude)
    FeatureSelection.select(feature)
}

private fun nearest(features: List<GlobeFeature>, focus: GlobeFocus): List<GlobeFeature> {
    val latitude = Math.toRadians(focus.latitude)
    return features.sortedByDescending { feature ->
        val otherLatitude = Math.toRadians(feature.latitude)
        sin(latitude) * sin(otherLatitude) + cos(latitude) * cos(otherLatitude) *
            cos(Math.toRadians(feature.longitude - focus.longitude))
    }.take(8)
}
