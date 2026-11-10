package com.example.earthexplorer.content

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.earthexplorer.data.AircraftLayerStore
import com.example.earthexplorer.data.EarthquakeLayerStore
import com.example.earthexplorer.data.MapLayers
import com.example.earthexplorer.data.PositionsLayerStore
import com.example.earthexplorer.data.WebcamLayerStore
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text

private data class LayerControl(
    val id: String,
    val title: String,
    val description: String,
    val visible: Boolean,
    val accent: Color,
    val toggle: () -> Unit,
)

/** Each additional map overlay can add one entry without changing the panel layout. */
@Composable
fun LayersPanel() {
    val capitalsVisible by MapLayers.capitalsVisible.collectAsState()
    val aircraftVisible by MapLayers.aircraftVisible.collectAsState()
    val earthquakesVisible by MapLayers.earthquakesVisible.collectAsState()
    val sunVisible by MapLayers.sunVisible.collectAsState()
    val positionsVisible by MapLayers.positionsVisible.collectAsState()
    val webcamsVisible by MapLayers.webcamsVisible.collectAsState()
    val gridVisible by MapLayers.gridVisible.collectAsState()
    val webcams by WebcamLayerStore.state.collectAsState()
    val aircraft by AircraftLayerStore.state.collectAsState()
    val earthquakes by EarthquakeLayerStore.state.collectAsState()
    val positions by PositionsLayerStore.state.collectAsState()
    val layers = listOf(
        LayerControl("capitals", "Capital cities", "200 markers · tap one to dive in", capitalsVisible,
            ExplorerPalette.gold, MapLayers::toggleCapitals),
        LayerControl("aircraft", "Airplanes", if (aircraft.loading) "Loading OpenSky…" else
            if (aircraft.error != null) "OpenSky unavailable · see details" else "OpenSky · manual snapshots", aircraftVisible,
            ExplorerPalette.aircraft, MapLayers::toggleAircraft),
        LayerControl("earthquakes", "Earthquakes", if (earthquakes.loading) "Loading USGS…" else
            if (earthquakes.error != null) "USGS unavailable · see details" else "Ring of Fire · M5.4+ · 12 months", earthquakesVisible,
            ExplorerPalette.quake, MapLayers::toggleEarthquakes),
        LayerControl("sun", "Sun & terminator", "Live daylight / night boundary", sunVisible,
            ExplorerPalette.sun, MapLayers::toggleSun),
        LayerControl("positions", "Positions", if (positions.loading && positions.features.isEmpty()) "Finding satellites…" else
            "ISS live · GPS constellation", positionsVisible, ExplorerPalette.satellite, MapLayers::togglePositions),
        LayerControl("webcams", "Webcams", if (webcams.loading) "Finding public cameras…" else
            "Big Ben & Austin traffic cameras", webcamsVisible, ExplorerPalette.webcam, MapLayers::toggleWebcams),
        LayerControl("grid", "Grid", "Meridians & parallels · 15°", gridVisible,
            ExplorerPalette.cyan, MapLayers::toggleGrid),
    )
    val online = layers.count { it.visible }

    ExplorerWindow {
        HudHeader("Command deck", "Map layers", trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Eyebrow("Online", ExplorerPalette.textSecondary)
                Text("$online / ${layers.size}", style = mono(PicoTheme.typography.titleMedium), color = ExplorerPalette.cyan)
            }
        })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(layers, key = { it.id }) { layer -> LayerRow(layer) }
        }
        HudDivider()
        Footnote("OpenSky · USGS · CelesTrak · TfL · Austin", Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
}

@Composable
private fun LayerRow(layer: LayerControl) {
    val shape = RoundedCornerShape(18.dp)
    val accent = layer.accent
    Button(
        onClick = layer.toggle,
        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)
            .border(1.dp, if (layer.visible) accent.copy(alpha = 0.7f) else ExplorerPalette.lineSoft, shape)
            .semantics {
                role = Role.Switch
                stateDescription = if (layer.visible) "On" else "Off"
            },
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (layer.visible) accent.copy(alpha = 0.14f) else ExplorerPalette.surface,
            contentColor = ExplorerPalette.textPrimary,
        ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(10.dp).background(if (layer.visible) accent else ExplorerPalette.textTertiary, CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(layer.title, style = PicoTheme.typography.titleMedium, color = ExplorerPalette.textPrimary)
                Text(layer.description, style = PicoTheme.typography.bodySmall,
                    color = if (layer.visible) accent.copy(alpha = 0.9f) else ExplorerPalette.textSecondary)
            }
            TogglePill(layer.visible, accent)
        }
    }
}
