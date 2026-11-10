package com.example.earthexplorer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.earthexplorer.data.EarthquakeFeedParser
import com.example.earthexplorer.data.FeatureLayer
import java.time.Instant
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EarthquakeFeedParserTest {
    private val now = Instant.parse("2026-09-21T12:00:00Z").toEpochMilli()

    private fun event(id: String = "us-test", longitude: String = "142.1", latitude: String = "38.3",
        magnitude: String = "6.1", depth: String = "24.5", time: Long = now - 60_000,
        type: String = "earthquake", status: String = "reviewed", tsunami: Int = 0): String = """{
        "type":"Feature","id":"$id","properties":{"mag":$magnitude,"magType":"mww",
        "place":"Near Japan","time":$time,"status":"$status","type":"$type","tsunami":$tsunami,
        "url":"https://earthquake.usgs.gov/earthquakes/eventpage/$id"},
        "geometry":{"type":"Point","coordinates":[$longitude,$latitude,$depth]}}
    """

    private fun feed(vararg events: String, generated: Long = now) = """{
        "type":"FeatureCollection","metadata":{"generated":$generated},"features":[${events.joinToString(",")}]
    }"""

    @Test fun convertsLongitudeLatitudeAndPreservesNumericDepth() {
        val parsed = EarthquakeFeedParser.parse(feed(event()), now)
        val quake = parsed.features.single()
        assertEquals("earthquake:us-test", quake.id)
        assertEquals(FeatureLayer.EARTHQUAKES, quake.layer)
        assertEquals(38.3, quake.latitude, 0.0)
        assertEquals(142.1, quake.longitude, 0.0)
        assertEquals(24.5, quake.depthKilometers!!, 0.0)
        assertEquals(6.1, quake.magnitude!!, 0.0)
        assertEquals("M 6.1 · Near Japan", quake.title)
        assertEquals("24.5 km", quake.details.first { it.label == "Depth" }.value)
        assertTrue(quake.details.first { it.label == "Occurred" }.value.endsWith("UTC"))
        assertEquals(now, parsed.updatedAtMillis)
        assertNull(parsed.error)
    }

    @Test fun keepsHistoricalEarthquakesWithinFiveYears() {
        val historic = Instant.parse("2022-03-01T00:00:00Z").toEpochMilli()
        assertEquals(historic, EarthquakeFeedParser.parse(feed(event(time = historic)), now)
            .features.single().observedAtMillis)
    }

    @Test fun excludesInvalidCoordinatesMagnitudeDatesAndNonEarthquakes() {
        val parsed = EarthquakeFeedParser.parse(feed(
            event(latitude = "91"), event(longitude = "181"), event(latitude = "null"),
            event(magnitude = "null"), event(magnitude = "0"), event(magnitude = "5.3"), event(type = "quarry blast"),
            event(status = "deleted"), event(time = now + 3_600_000),
            event(time = Instant.parse("2021-09-20T23:59:59Z").toEpochMilli()),
            event(id = "valid", magnitude = "5.4"),
        ), now)
        assertEquals(listOf("earthquake:valid"), parsed.features.map { it.id })
    }

    @Test fun deDuplicatesEventsAndSortsNewestFirst() {
        val parsed = EarthquakeFeedParser.parse(feed(event("older", time = now - 120_000),
            event("newer"), event("older", time = now - 120_000)), now)
        assertEquals(listOf("earthquake:newer", "earthquake:older"), parsed.features.map { it.id })
    }

    @Test fun tsunamiFlagIsExplicitlyNotAWarning() {
        val quake = EarthquakeFeedParser.parse(feed(event(tsunami = 1)), now).features.single()
        assertEquals("USGS ocean-event flag; not a tsunami warning", quake.details.first { it.label == "Tsunami flag" }.value)
        val noFlag = EarthquakeFeedParser.parse(feed(event()), now).features.single()
        assertFalse(noFlag.details.any { it.label == "Tsunami flag" })
    }

    @Test fun missingDepthIsNotPresentedAsZero() {
        val quake = EarthquakeFeedParser.parse(feed(event(depth = "null")), now).features.single()
        assertNull(quake.depthKilometers)
        assertEquals("Not reported", quake.details.first { it.label == "Depth" }.value)
    }

    @Test fun zeroCoordinatesAreValidAndNegativeDepthDoesNotLiftTheEpicenter() {
        val quake = EarthquakeFeedParser.parse(feed(event(latitude = "0", longitude = "0", depth = "-1.2")), now)
            .features.single()
        assertEquals(0.0, quake.latitude, 0.0)
        assertEquals(0.0, quake.longitude, 0.0)
        assertEquals(-1.2, quake.depthKilometers!!, 0.0)
        assertEquals("-1.2 km", quake.details.first { it.label == "Depth" }.value)
        assertEquals(0.0, quake.altitudeMeters, 0.0)
    }

    @Test fun oldCatalogTimestampIsClearlyMarkedDelayed() {
        val parsed = EarthquakeFeedParser.parse(feed(event(), generated = now - 2 * 3_600_000), now)
        assertNotNull(parsed.error)
        assertEquals(now - 2 * 3_600_000, parsed.updatedAtMillis)
        assertEquals(1, parsed.features.size)
    }

    @Test(expected = JSONException::class)
    fun rejectsWrongDocumentType() { EarthquakeFeedParser.parse("{\"type\":\"Point\",\"features\":[]}", now) }
}
