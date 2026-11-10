package com.example.earthexplorer.data

/** The ion geocoder exposes formatted labels, not typed Google address components. */
data class GeocoderPlace(val label: String, val spanKilometers: Double? = null)

data class PlaceLabels(val city: String? = null, val region: String? = null, val country: String? = null) {
    companion object {
        private val administration = Regex(
            "(?i)(\\b(county|district|province|prefecture|region|state|arrondissement|kreis|landkreis|kreisfreie|department)\\b|^Co\\.\\s|^Greater\\s)",
        )
        private val street = Regex(
            "(?i)(\\b(street|road|avenue|lane|highway|rue|calle|via|strasse|straße|square|plaza|boulevard)\\b|\\b(st|rd|ave|str)\\.?$)",
        )
        private val regionCode = Regex("^[A-Z]{2,3}$")
        private val leadingPostal = Regex("^(?:[A-Z]{1,2}-)?\\d{4,6}\\s+")
        private val trailingPostal = Regex("\\s+\\d{4,6}(?:-\\d{3,4})?$")
        private val britishPostal = Regex("\\s+[A-Z]{1,2}\\d[A-Z\\d]?(?:\\s*\\d[A-Z]{2})?$", RegexOption.IGNORE_CASE)
        private val canadianPostal = Regex("\\s+[A-Z]\\d[A-Z]\\s?\\d[A-Z]\\d$", RegexOption.IGNORE_CASE)

        /** Prefer locality-sized labels and leave an unknown city blank instead of using a street. */
        fun from(features: List<GeocoderPlace>): PlaceLabels {
            val country = features.lastOrNull { ',' !in it.label }?.label?.trim()?.takeIf { it.isNotBlank() }
            val candidates = features.mapNotNull { feature ->
                val parts = feature.label.split(',').map(String::trim)
                if (parts.size !in 2..3 || parts.any(String::isBlank)) return@mapNotNull null
                // Country/state bounds can span hundreds of kilometres; point-address viewports
                // are tiny. Neither can safely establish a municipality from its label alone.
                if (feature.spanKilometers?.let { it > 180 || it < 0.45 } == true) return@mapNotNull null
                val explicitRegion = parts.getOrNull(1)?.let(::withoutPostalCode)
                    ?.takeIf { parts.size == 3 && regionCode.matches(it) }
                if (parts.size == 3 && explicitRegion == null) return@mapNotNull null
                var city = withoutPostalCode(parts.first())
                var region = explicitRegion
                // Australian locality results commonly put the state alongside the postcode.
                if (parts.size == 2 && parts.last() == "Australia") {
                    val state = city.substringAfterLast(' ')
                    if (state in setOf("ACT", "NSW", "NT", "QLD", "SA", "TAS", "VIC", "WA")) {
                        region = state
                        city = city.substringBeforeLast(' ')
                    }
                }
                if (city.isBlank() || city.any(Char::isDigit) || administration.containsMatchIn(city) ||
                    street.containsMatchIn(city) || city.contains('+')) return@mapNotNull null
                val score = if (explicitRegion != null) 0 else 1
                score to PlaceLabels(city, region, country ?: parts.last())
            }
            return candidates.minByOrNull { it.first }?.second ?: PlaceLabels(country = country)
        }

        private fun withoutPostalCode(value: String): String = value
            .replace(leadingPostal, "")
            .replace(trailingPostal, "")
            .replace(britishPostal, "")
            .replace(canadianPostal, "")
            .trim()
    }
}
