package com.example.earthexplorer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The independent video window follows a camera selection, not the movable terrain center. */
object WebcamSelection {
    private val _camera = MutableStateFlow<WebcamCamera?>(null)
    val camera = _camera.asStateFlow()

    fun open(camera: WebcamCamera) { _camera.value = camera }
    fun clear() { _camera.value = null }
}
