package com.example.earthexplorer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ExplorerMotionPhase(val message: String?) {
    EARTH(null),
    PREPARING_CITY("Target locked · streaming photorealistic tiles…"),
    TO_CITY("Dissolving Earth into the grid…"),
    BUILDING_CITY("Raising photorealistic tiles…"),
    CITY(null),
    TO_EARTH("Reforming Earth…"),
}

/** Presentation state is separate from the asynchronous tile download status. */
internal object ExplorerMotion {
    private val _phase = MutableStateFlow(ExplorerMotionPhase.EARTH)
    val phase = _phase.asStateFlow()
    fun setPhase(phase: ExplorerMotionPhase) { _phase.value = phase }
}
