package com.example.earthexplorer.content

import com.example.earthexplorer.data.WebcamCamera
import com.example.earthexplorer.data.refreshedWebcamSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebcamReconciliationTest {
    private val camera = WebcamCamera(
        id = "tfl-00001.04502", title = "Westminster Bridge/York Rd",
        city = "London", country = "United Kingdom", latitude = 51.5009, longitude = -0.11762,
        provider = "Transport for London", sourceUrl = "https://tfl.gov.uk/traffic/status/",
        imageUrl = "https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00001.04502.jpg",
    )

    @Test
    fun successfulRemovalClosesCameraSelection() {
        assertNull(refreshedWebcamSelection(camera, emptyList(), setOf("tfl-")))
    }

    @Test
    fun unavailableProviderRetainsExistingSelection() {
        assertEquals(camera, refreshedWebcamSelection(camera, emptyList(), setOf("austin-")))
    }

    @Test
    fun successfulRefreshUpdatesSelectedCameraMetadata() {
        val updated = camera.copy(title = "Updated camera title", latitude = 51.501)
        assertEquals(updated, refreshedWebcamSelection(camera, listOf(updated), setOf("tfl-")))
    }

    @Test
    fun refreshDoesNotOpenAnUnselectedCamera() {
        assertNull(refreshedWebcamSelection(null, listOf(camera), setOf("tfl-")))
    }
}
