package com.example.earthexplorer.content

import com.example.earthexplorer.data.EarthquakeFeedParser
import com.example.earthexplorer.data.EarthquakeLayerStore
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class EarthquakeCatalogTest {
    @Test fun referenceQueryUsesRollingTwelveMonthHistoryAndM54Threshold() {
        val now = Instant.parse("2026-09-21T13:25:00Z").toEpochMilli()
        assertEquals("https://earthquake.usgs.gov/fdsnws/event/1/query?format=geojson&starttime=2025-09-21&minmagnitude=5.4&limit=600&orderby=time",
            EarthquakeLayerStore.queryUrl(now))
    }

    @Test fun historyStartIsUtcAndHandlesLeapDay() {
        val now = Instant.parse("2024-02-29T00:15:00Z").toEpochMilli()
        assertEquals(Instant.parse("2023-02-28T00:00:00Z").toEpochMilli(),
            EarthquakeFeedParser.windowStartMillis(now))
    }

    @Test fun disabledStoreDoesNotFetchOnRefresh() {
        EarthquakeLayerStore.setEnabled(false)
        val before = EarthquakeLayerStore.state.value
        EarthquakeLayerStore.refresh()
        assertEquals(before, EarthquakeLayerStore.state.value)
        assertFalse(EarthquakeLayerStore.state.value.loading)
    }
}
