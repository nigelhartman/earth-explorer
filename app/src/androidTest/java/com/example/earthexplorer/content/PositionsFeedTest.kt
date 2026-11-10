package com.example.earthexplorer.content

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.earthexplorer.data.PositionsLayerStore
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PositionsFeedTest {
    private val orbit = """{
        "OBJECT_NAME":"GPS TEST (PRN 01)","NORAD_CAT_ID":12345,
        "EPOCH":"2026-09-21T06:18:50.138784","MEAN_MOTION":2.005,
        "ECCENTRICITY":0.01,"INCLINATION":55.0,"RA_OF_ASC_NODE":210.0,
        "ARG_OF_PERICENTER":300.0,"MEAN_ANOMALY":230.0
    }"""

    @Test fun parsesCelestrakOmmEpochAsUtcAndSkipsMalformedRows() {
        val parsed = PositionsLayerStore.parseOrbitElements("[$orbit,{},$orbit]")
        assertEquals(1, parsed.size)
        assertEquals(Instant.parse("2026-09-21T06:18:50.138784Z").toEpochMilli(), parsed.single().epochMillis)
        assertEquals(12345L, parsed.single().catalogId)
        val feature = requireNotNull(parsed.single().featureAt(parsed.single().epochMillis))
        assertTrue(feature.latitude.isFinite())
        assertTrue(feature.altitudeMeters > 10_000_000.0)
    }

    @Test fun parsesIssProviderUnitsAndOriginalTimestamp() {
        val report = PositionsLayerStore.parseIssPosition("""{
            "id":25544,"latitude":-37.16,"longitude":-130.76,
            "altitude":429.479,"velocity":27556.16,"visibility":"eclipsed",
            "timestamp":1789989744,"units":"kilometers"
        }""")
        assertEquals(1_789_989_744_000L, report.timestampMillis)
        assertEquals(429_479.0, report.featureAt(report.timestampMillis)?.altitudeMeters ?: 0.0, 0.01)
        assertEquals("eclipsed", report.visibility)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedIssResponseUnits() {
        PositionsLayerStore.parseIssPosition("""{"id":25544,"units":"miles"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsImpossibleIssTimestampWithoutOverflow() {
        PositionsLayerStore.parseIssPosition("""{
            "id":25544,"units":"kilometers","timestamp":9223372036854775807
        }""")
    }
}
