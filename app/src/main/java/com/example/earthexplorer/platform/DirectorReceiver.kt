package com.example.earthexplorer.platform

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import com.example.earthexplorer.data.AssistantDemo
import com.example.earthexplorer.data.GlobeSelection
import com.example.earthexplorer.data.LocationStore
import com.example.earthexplorer.data.MapLayers
import com.example.earthexplorer.data.PanelVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos

/**
 * Debug-only remote control for demo recordings. Only registered on debuggable builds; every
 * command maps onto the same store calls the real gestures use, so a recording made through it
 * shows the ordinary app behaviour, just without a hand in front of it.
 *
 * adb shell am broadcast -a com.example.earthexplorer.DIRECT --es cmd city --ef lat 52.520815 \
 *     --ef lon 13.411050 --es name Berlin --es country Germany
 */
class DirectorReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        Log.i(TAG, "cmd=$cmd extras=${intent.extras}")
        when (cmd) {
            "city" -> {
                val lat = intent.getFloatExtra("lat", 52.520815f).toDouble()
                val lon = intent.getFloatExtra("lon", 13.411050f).toDouble()
                LocationStore.select(lat, lon, intent.getStringExtra("name"), intent.getStringExtra("country"))
            }
            "earth" -> LocationStore.showGlobe()
            "panels" -> PanelVisibility.set(intent.getBooleanExtra("on", true))
            "focus" -> GlobeSelection.focusOn(
                intent.getFloatExtra("lat", 0f).toDouble(), intent.getFloatExtra("lon", 0f).toDouble(),
                intent.getBooleanExtra("blink", false),
            )
            "assist" -> {
                val hold = intent.getLongExtra("hold", 3_000L)
                running?.cancel()
                running = scope.launch {
                    AssistantDemo.pressStart()
                    delay(hold)
                    AssistantDemo.pressEnd()
                }
            }
            "pan" -> {
                val dx = intent.getFloatExtra("dx", 0f)
                val dy = intent.getFloatExtra("dy", 0f)
                val ms = intent.getLongExtra("ms", 2_000L)
                running?.cancel()
                running = scope.launch { animatePan(dx, dy, ms) }
            }
            "layer" -> {
                val on = intent.getBooleanExtra("on", true)
                when (intent.getStringExtra("name")) {
                    "capitals" -> MapLayers.setCapitalsVisible(on)
                    "aircraft" -> MapLayers.setAircraftVisible(on)
                    "earthquakes" -> MapLayers.setEarthquakesVisible(on)
                    "sun" -> MapLayers.setSunVisible(on)
                    "positions" -> MapLayers.setPositionsVisible(on)
                    "webcams" -> MapLayers.setWebcamsVisible(on)
                    "grid" -> MapLayers.setGridVisible(on)
                }
            }
            "stop" -> running?.cancel()
            "reset" -> {
                running?.cancel()
                AssistantDemo.reset()
                PanelVisibility.set(false)
                LocationStore.showGlobe()
            }
            else -> Log.w(TAG, "unknown cmd $cmd")
        }
    }

    /** Eased pan in small steps, the way a slow hand drag arrives from the gesture detector. */
    private suspend fun animatePan(dx: Float, dy: Float, ms: Long) {
        val steps = (ms / 16L).toInt().coerceAtLeast(1)
        var previous = 0f
        LocationStore.beginPanGesture("Director")
        try {
            for (step in 1..steps) {
                val t = step.toFloat() / steps
                val eased = (1f - cos((t * PI).toFloat())) / 2f
                val delta = eased - previous
                previous = eased
                LocationStore.panCityFromGesture(dx * delta, dy * delta, 0f)
                delay(16)
            }
        } finally {
            LocationStore.endPanGesture()
        }
    }

    companion object {
        private const val TAG = "Director"
        const val ACTION = "com.example.earthexplorer.DIRECT"

        fun register(app: Application) {
            val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            if (!debuggable) return
            val filter = IntentFilter(ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(DirectorReceiver(), filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(DirectorReceiver(), filter)
            }
            Log.i(TAG, "Director receiver registered")
        }
    }
}
