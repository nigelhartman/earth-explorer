package com.example.earthexplorer.data

enum class FeatureLayer { AIRCRAFT, EARTHQUAKES, SUN, POSITIONS, WEBCAMS }

data class FeatureDetail(val label: String, val value: String)

/** A real observation or explicitly labelled orbital prediction, shared by 3D and 2D views. */
data class GlobeFeature(
    val id: String,
    val layer: FeatureLayer,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val subtitle: String = "",
    val altitudeMeters: Double = 0.0,
    val headingDegrees: Double = 0.0,
    val observedAtMillis: Long,
    val details: List<FeatureDetail> = emptyList(),
    val source: String,
    val sourceUrl: String? = null,
    val magnitude: Double? = null,
    val depthKilometers: Double? = null,
    val highlighted: Boolean = false,
)

data class LayerFeed(
    val features: List<GlobeFeature> = emptyList(),
    val loading: Boolean = false,
    val updatedAtMillis: Long? = null,
    val error: String? = null,
    val note: String = "",
)
