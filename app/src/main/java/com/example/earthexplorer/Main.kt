package com.example.earthexplorer

import androidx.compose.ui.unit.dp
import com.example.earthexplorer.content.GlobeVolume
import com.example.earthexplorer.content.LocationPanel
import com.example.earthexplorer.content.AssistantPanel
import com.example.earthexplorer.content.LayersPanel
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import com.pico.spatial.ui.platform.resize.VolumeBasePanelType

const val GLOBE_WINDOW = "GlobeVolume"
const val LOCATION_WINDOW = "LocationPanel"
const val LAYERS_WINDOW = "LayersPanel"
const val ASSISTANT_WINDOW = "AssistantPanel"

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            PicoTheme {
                GlobeVolume()
            }
        }

        WindowContainer(
            id = LAYERS_WINDOW,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 360.dp, height = 620.dp),
            enableMaterialBackground = false,
            placement = {
                containers
                    .firstOrNull { it.id == GLOBE_WINDOW }
                    ?.let { Placement.left(it, 123.dp) }
                    ?: Placement.none()
            },
        ) {
            PicoTheme {
                LayersPanel()
            }
        }

        WindowContainer(
            id = ASSISTANT_WINDOW,
            form = Form.Volumetric,
            defaultSize = WindowContainerSize(width = 360.dp, height = 300.dp, depth = 300.dp),
            enableMaterialBackground = false,
            defaultVolumeBasePanelType = VolumeBasePanelType.None,
            placement = {
                containers
                    .firstOrNull { it.id == LAYERS_WINDOW }
                    ?.let { Placement.bottom(it, 48.dp) }
                    ?: containers.firstOrNull { it.id == GLOBE_WINDOW }
                        ?.let { Placement.left(it, 123.dp) }
                    ?: Placement.none()
            },
        ) {
            PicoTheme {
                AssistantPanel()
            }
        }

        WindowContainer(
            id = LOCATION_WINDOW,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 540.dp, height = 660.dp),
            enableMaterialBackground = false,
            placement = {
                containers
                    .firstOrNull { it.id == GLOBE_WINDOW }
                    ?.let { Placement.right(it, 123.dp) }
                    ?: Placement.none()
            },
        ) {
            PicoTheme {
                LocationPanel()
            }
        }
    }
