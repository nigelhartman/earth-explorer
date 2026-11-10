package com.example.earthexplorer.platform

import android.content.pm.ApplicationInfo
import android.os.Bundle
import com.example.earthexplorer.data.LocationStore
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity

class LaunchActivity : SpatialLaunchActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val debuggable =
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (
            debuggable &&
                savedInstanceState == null &&
                intent.getBooleanExtra("demo_city", false)
        ) {
            LocationStore.select(
                intent.getDoubleExtra("demo_latitude", 52.520815),
                intent.getDoubleExtra("demo_longitude", 13.411050),
            )
        }
    }
}
