package com.example.earthexplorer.content

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.example.earthexplorer.data.CapitalCities
import com.example.earthexplorer.data.GlobeSelection
import com.example.earthexplorer.data.LocationStore
import com.example.earthexplorer.data.MapLayers
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text

@Composable
fun CapitalPicker() {
    val focus by GlobeSelection.focus.collectAsState()
    val choice by GlobeSelection.choice.collectAsState()
    val capitalsVisible by MapLayers.capitalsVisible.collectAsState()
    var query by remember { mutableStateOf("") }
    val cities = remember(query, focus, choice) {
        if (query.isBlank() && choice != null) choice!!.cities
        else CapitalCities.search(query, focus.latitude, focus.longitude)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (capitalsVisible) "Gold markers are capitals" else "Choose a place to explore",
            style = PicoTheme.typography.titleMedium, color = ExplorerPalette.textPrimary)
        Footnote(if (capitalsVisible) "Tap a marker or choose a name below to dive into its city center. Drag the globe to rotate."
            else "Tap any point on Earth or search for a capital below. Capital markers are hidden; turn them on in Map layers.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Berlin", "Paris", "London").forEach { name ->
                val capital = CapitalCities.all.first { it.name == name }
                HudButton(name, { GlobeSelection.open(capital) }, accent = ExplorerPalette.gold)
            }
        }
        val fieldShape = RoundedCornerShape(14.dp)
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = PicoTheme.typography.bodyLarge.copy(color = ExplorerPalette.textPrimary),
            cursorBrush = SolidColor(ExplorerPalette.cyan),
            modifier = Modifier.fillMaxWidth()
                .background(ExplorerPalette.surface, fieldShape)
                .border(1.dp, ExplorerPalette.line, fieldShape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            decorationBox = { input ->
                Box {
                    if (query.isEmpty()) Text("Search capital or country…", color = ExplorerPalette.textTertiary)
                    input()
                }
            },
        )
        Eyebrow(if (choice != null && query.isBlank()) "Close-together capitals — choose one"
            else if (query.isBlank()) "Nearest the center of your view"
            else "${cities.size} matching capitals", ExplorerPalette.textSecondary)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cities, key = { it.id }) { capital ->
                FeatureCard(capital.name, capital.country, ExplorerPalette.gold, { GlobeSelection.open(capital) })
            }
            if (cities.isEmpty()) item { Footnote("No matching capital. You can still tap any point on Earth.") }
        }
        choice?.let { selected ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton("Use touched point", {
                    GlobeSelection.clearChoice()
                    LocationStore.select(selected.point.latitude, selected.point.longitude)
                }, filled = true)
                HudButton("All capitals", GlobeSelection::clearChoice)
            }
        }
        Footnote("Capital locations · Natural Earth. Detailed 3D building coverage varies by city.")
    }
}
