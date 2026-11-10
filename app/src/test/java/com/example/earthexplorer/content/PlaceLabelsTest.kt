package com.example.earthexplorer.content

import com.example.earthexplorer.data.GeocoderPlace
import com.example.earthexplorer.data.PlaceLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceLabelsTest {
    @Test fun berlinUsesCityNotStreetOrDistrict() {
        val place = labels("Spandauer Str., 10178 Berlin, Germany", "Mitte, Berlin, Germany",
            "Berlin, Germany", "Kreisfreie Stadt Berlin, Berlin, Germany", "Germany")
        assertEquals("Berlin", place.city)
        assertEquals("Germany", place.country)
    }

    @Test fun dublinAvoidsStreetAndCounty() {
        val place = labels("9 Fitzwilliam Square E, Dublin, D02 FX96, Ireland",
            "St Peters, Dublin, Co. Dublin, Ireland", "Dublin City, Co. Dublin, Ireland",
            "Dublin, Ireland", "Co. Dublin, Ireland", "Ireland")
        assertEquals("Dublin", place.city)
    }

    @Test fun newYorkPreservesStateAndFullCountryName() {
        val place = labels("1060 West St, New York, NY 10007, USA", "New York, NY 10007, USA",
            "New York, NY, USA", "New York, USA", "United States")
        assertEquals(PlaceLabels("New York", "NY", "United States"), place)
    }

    @Test fun ukPostalDistrictIsNotPartOfCityName() {
        val place = labels("59 A4, London WC2N 5DU, UK", "London WC2N, UK",
            "City of Westminster, London, UK", "London, UK", "Greater London, UK", "United Kingdom")
        assertEquals("London", place.city)
        assertEquals("United Kingdom", place.country)
    }

    @Test fun europeanPostalLocalityIsPreferredToLargerRegion() {
        assertEquals("Zürich", labels("Bahnhofquai, 8001 Zürich, Switzerland",
            "8001 Zürich, Switzerland", "Zurich, Switzerland", "Switzerland").city)
        assertEquals("Paris", labels("4 Pl. de l'Hôtel de Ville, 75004 Paris, France",
            "75004 Paris, France", "Paris, France", "Île-de-France, France", "France").city)
    }

    @Test fun japanesePostalCodeDoesNotBecomeCity() {
        assertEquals("Tokyo", labels("168-0063, Japan", "Suginami City, Tokyo, Japan",
            "Tokyo, Japan", "Japan").city)
    }

    @Test fun australianStateAndPostalCodeAreSeparated() {
        assertEquals(PlaceLabels("Melbourne", "VIC", "Australia"),
            labels("Melbourne VIC 3000, Australia", "Australia"))
    }

    @Test fun canadianProvinceAndPostalCodeAreSeparated() {
        assertEquals(PlaceLabels("Toronto", "ON", "Canada"),
            labels("Toronto, ON M5V 2T6, Canada", "Ontario, Canada", "Canada"))
    }

    @Test fun streetOnlyResultsDoNotInventACity() {
        assertNull(labels("10 Main Street, Ireland", "Co. Dublin, Ireland", "Ireland").city)
        assertNull(labels("Unnamed Road, Germany", "Germany").city)
        assertNull(labels("Main St, United States", "United States").city)
        assertNull(PlaceLabels.from(listOf(GeocoderPlace("City Hall, Germany", 0.3),
            GeocoderPlace("Germany", 900.0))).city)
    }

    @Test fun countryOrVeryLargeRegionDoesNotBecomeCity() {
        val result = PlaceLabels.from(listOf(GeocoderPlace("New York, USA", 850.0),
            GeocoderPlace("United States", 5000.0)))
        assertEquals(PlaceLabels(country = "United States"), result)
        assertEquals(PlaceLabels(), PlaceLabels.from(emptyList()))
    }

    @Test fun groundViewUsesUnknownWhenReverseGeocoderFindsOnlyACountry() {
        assertEquals(CityLabelText("Unknown", "Germany"), cityLabelText(null, "Germany"))
        assertEquals(CityLabelText("Unknown", "Germany"), cityLabelText("  ", " Germany "))
    }

    @Test fun groundViewAlwaysHasALabelBeforeCountryLookupFinishes() {
        assertEquals(CityLabelText("Unknown", null), cityLabelText(null, null))
    }

    private fun labels(vararg values: String): PlaceLabels = PlaceLabels.from(values.map { GeocoderPlace(it) })
}
