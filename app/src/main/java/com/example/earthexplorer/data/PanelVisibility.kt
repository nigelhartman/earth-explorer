package com.example.earthexplorer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cinema mode: one switch hides or shows every 2D window so the 3D volume stands alone. */
object PanelVisibility {
    /** Starts hidden so the globe owns the scene; the PANELS button in the volume brings them back. */
    private val _visible = MutableStateFlow(false)
    val visible = _visible.asStateFlow()
    fun toggle() { _visible.value = !_visible.value }
    fun set(value: Boolean) { _visible.value = value }
}
