package com.example.earthexplorer.content

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.example.earthexplorer.BuildConfig
import com.example.earthexplorer.data.LocationStore
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture

/** Accepts both entity hits and the emulator's view-level pointer events. */
@Composable
internal fun Modifier.explorerDragInput(scene: Any): Modifier {
    val context = LocalContext.current
    return pointerInput(scene) {
        var accepted = false
        detectSpatialDragGesture(context,
            onDragStart = { _, value ->
                accepted = value.targetEntity?.getName() !in setOf(CITY_STATUS_NAME, CITY_CLOSE_NAME)
                if (accepted) {
                    LocationStore.beginPanGesture(value.interactionKind.toString())
                    if (BuildConfig.DEBUG) Log.i("ExplorerDrag", "Begin: ${value.targetEntity?.getName() ?: "view"} (${value.interactionKind})")
                }
            },
            onDragEnd = { if (accepted) LocationStore.endPanGesture(); accepted = false },
            onDragCancel = { if (accepted) LocationStore.endPanGesture(); accepted = false },
        ) { drag ->
            if (accepted) LocationStore.panCityFromGesture(drag.dragAmount.x, drag.dragAmount.y, drag.dragAmount.z)
        }
    }.pointerInput(scene) {
        detectSpatialTapGesture(
            context,
            TargetEntity.any { it.getName() == CITY_CLOSE_NAME },
        ) {
            LocationStore.showGlobe()
        }
    }
}
