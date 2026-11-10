package com.example.earthexplorer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.earthexplorer.data.AircraftSnapshotParser
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AircraftSnapshotTest {
    @Test fun parsesOpenSkyPositionalArrayAndDropsStaleMissingAndMalformedRows() {
        val snapshot = AircraftSnapshotParser.parse("""{
          "time": 1010,
          "states": [
            ["3c6444"," DLH123 ","Germany",1000,1008,13.4,52.5,10000,false,250,95,5,null,10150,"1234",false,0],
            ["3c6445",null,"Germany",null,1008,13.4,52.5,10000,false,250,95,5,null,10150,null,false,0],
            ["3c6446",null,"Germany",800,1008,13.4,52.5,10000,false,250,95,5,null,10150,null,false,0],
            ["3c6447",null,"Germany",1000,1008,null,null,10000,false,250,95,5,null,10150,null,false,0],
            null, 42, []
          ]
        }""")
        assertEquals(1_010_000L, snapshot.timeMillis)
        assertEquals(1, snapshot.features.size)
        val aircraft = snapshot.features.single()
        assertEquals("DLH123", aircraft.title)
        assertEquals(13.4, aircraft.longitude, 0.0)
        assertEquals(52.5, aircraft.latitude, 0.0)
        assertEquals(10_150.0, aircraft.altitudeMeters, 0.0)
        assertTrue(aircraft.details.any { it.label == "Squawk" && it.value == "1234" })
    }

    @Test fun nullStateArrayIsAValidEmptyProviderSnapshot() {
        assertTrue(AircraftSnapshotParser.parse("""{"time":1010,"states":null}""").features.isEmpty())
    }

    @Test fun duplicateAircraftUsesNewestPosition() {
        val snapshot = AircraftSnapshotParser.parse("""{
          "time":1010,"states":[
            ["3c6444","NEW",null,1005,1008,13.4,52.5],
            ["3c6444","OLD",null,1000,1008,13.3,52.4]
          ]
        }""")
        assertEquals(1, snapshot.features.size)
        assertEquals("NEW", snapshot.features.single().title)
    }

    @Test fun rejectsUntimedSnapshot() {
        try {
            AircraftSnapshotParser.parse("""{"states":[]}""")
            fail("Expected missing timestamp rejection")
        } catch (_: IOException) { }
    }
}
