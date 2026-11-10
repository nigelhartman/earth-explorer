package com.example.earthexplorer.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.example.earthexplorer.data.AircraftLayerStore
import com.example.earthexplorer.data.EarthquakeLayerStore
import com.example.earthexplorer.data.ExplorerMotion
import com.example.earthexplorer.data.FeatureLayer
import com.example.earthexplorer.data.FeatureSelection
import com.example.earthexplorer.data.GeoFrame
import com.example.earthexplorer.data.LocationStore
import com.example.earthexplorer.data.MapLayers
import com.example.earthexplorer.data.PositionsLayerStore
import com.example.earthexplorer.data.WebcamLayerStore
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text

@Composable
fun LocationPanel() {
    val selected by LocationStore.selected.collectAsState()
    val currentLocation by LocationStore.viewLocation.collectAsState()
    val renderState by LocationStore.renderState.collectAsState()
    val center by LocationStore.cityCenter.collectAsState()
    val motion by ExplorerMotion.phase.collectAsState()
    val selectedFeature by FeatureSelection.selected.collectAsState()
    val aircraft by AircraftLayerStore.state.collectAsState()
    val earthquakes by EarthquakeLayerStore.state.collectAsState()
    val positions by PositionsLayerStore.state.collectAsState()
    val webcams by WebcamLayerStore.state.collectAsState()
    val webcamsVisible by MapLayers.webcamsVisible.collectAsState()
    val aircraftVisible by MapLayers.aircraftVisible.collectAsState()
    val earthquakesVisible by MapLayers.earthquakesVisible.collectAsState()
    val sunVisible by MapLayers.sunVisible.collectAsState()
    val positionsVisible by MapLayers.positionsVisible.collectAsState()
    var showLayers by remember { mutableStateOf(false) }
    var previousLayers by remember { mutableStateOf(emptySet<FeatureLayer>()) }
    val enabledLayers = buildSet {
        if (aircraftVisible) add(FeatureLayer.AIRCRAFT)
        if (earthquakesVisible) add(FeatureLayer.EARTHQUAKES)
        if (sunVisible) add(FeatureLayer.SUN)
        if (positionsVisible) add(FeatureLayer.POSITIONS)
        if (webcamsVisible) add(FeatureLayer.WEBCAMS)
    }
    LaunchedEffect(enabledLayers) {
        if ((enabledLayers - previousLayers).isNotEmpty()) showLayers = true
        previousLayers = enabledLayers
    }

    selectedFeature?.let { selectedItem ->
        val feed = when (selectedItem.layer) {
            FeatureLayer.AIRCRAFT -> aircraft
            FeatureLayer.EARTHQUAKES -> earthquakes
            FeatureLayer.POSITIONS -> positions
            FeatureLayer.SUN -> null
            FeatureLayer.WEBCAMS -> webcams
        }
        val latest = feed?.features?.firstOrNull { it.id == selectedItem.id }
        FeatureDetailsPanel(
            feature = latest ?: selectedItem,
            feed = feed,
            presentInCurrentFeed = feed == null || latest != null,
        )
        return
    }

    ExplorerWindow(bootDelayMillis = 250) {
        val origin = selected
        if (origin == null) {
            HudHeader("Earth Explorer", "Explore Earth", trailing = { UtcClock() })
            motion.message?.let {
                Box(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) { StatusLine(it) }
            }
            PillTabs(listOf("Places", "Live layers"), if (showLayers) 1 else 0, { showLayers = it == 1 },
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            Column(Modifier.weight(1f).fillMaxWidth()) {
                if (showLayers) LayerOverview() else CapitalPicker()
            }
            return@ExplorerWindow
        }
        val location = currentLocation ?: origin
        val coordinate = GeoFrame(origin.latitude, origin.longitude).coordinate(center)
        val cityName = location.city ?: if (location.loading) "Finding city…" else "Unknown"
        val countryName = location.country ?: if (location.loading) "Looking up country…" else "Country unavailable"
        HudHeader(countryName, cityName, trailing = { UtcClock() })

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            location.region?.takeIf { it != location.city && it != location.country }?.let {
                Text(it, style = PicoTheme.typography.bodyLarge, color = ExplorerPalette.textSecondary)
            }
            val loaded = renderState.loadedTiles
            val requested = renderState.requestedTiles
            val failed = renderState.error != null
            val streaming = motion.message != null || loaded == 0 || (requested > 0 && loaded < requested)
            GlowCard(accent = if (failed) ExplorerPalette.danger else ExplorerPalette.cyan) {
                Eyebrow(if (failed) "Coverage" else "Status", if (failed) ExplorerPalette.danger else ExplorerPalette.cyan)
                Text(motion.message ?: renderState.status, style = PicoTheme.typography.titleMedium,
                    color = ExplorerPalette.textPrimary)
                if (streaming && !failed) HudProgress(if (requested > 0) loaded / requested.toFloat() else 0f)
                if (failed) Footnote("Some city detail could not be loaded. You can keep exploring nearby.")
            }
            Eyebrow("Center of view", ExplorerPalette.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Latitude", latitudeText(coordinate.first), Modifier.weight(1f))
                StatTile("Longitude", longitudeText(coordinate.second), Modifier.weight(1f))
            }
            Footnote("Drag the city to explore. Coordinates follow the center of the 3D window.")
            if (location.error != null) {
                Footnote("Place information is temporarily unavailable. The center coordinates are still current.")
            }
        }

        HudDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Footnote(renderState.attribution.ifBlank { "Google Maps · Cesium ion" })
            Footnote("Cesium ion · Upgrade for commercial use")
        }
    }
}
