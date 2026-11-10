package com.example.earthexplorer.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.example.earthexplorer.mainApp
import com.example.earthexplorer.data.PositionsLayerStore

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PositionsLayerStore.configureCache(cacheDir.resolve("positions"))
        DirectorReceiver.register(this)
        launch(::mainApp)
    }
}
