package com.example.earthexplorer.content

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebcamMediaTest {
    @Test fun standardTrafficCameraImageIsLetterboxedWithoutStretching() {
        val size = fitWebcamVideo(0.68f, 0.38f, 352, 288)
        assertEquals(0.38f, size.second, 0.0001f)
        assertEquals(352f / 288f, size.first / size.second, 0.0001f)
    }

    @Test fun wideVideoUsesAvailableWidthWithoutCropping() {
        assertEquals(0.68f to 0.34f, fitWebcamVideo(0.68f, 0.5f, 1280, 640))
    }

    @Test fun missingTimestampIsNotRepresentedAsLive() {
        val status = webcamFreshnessText(null, 1_000L, 2_000L)
        assertTrue(status.contains("capture time unavailable"))
        assertFalse(status.contains("Live", ignoreCase = true))
    }

    @Test fun oldProviderFileIsExplicitlyMarkedPotentiallyStale() {
        val status = webcamFreshnessText(1_000L, 901_000L, 901_000L)
        assertTrue(status.contains("15 min ago"))
        assertTrue(status.contains("May be stale"))
    }

    @Test fun publicHttpsMediaAddressIsAccepted() {
        assertEquals("s3-eu-west-1.amazonaws.com", validateWebcamMediaUrl(
            "https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00001.03602.mp4").host)
    }

    @Test(expected = IOException::class) fun cleartextCameraAddressIsRejected() {
        validateWebcamMediaUrl("http://example.com/camera.jpg")
    }

    @Test(expected = IOException::class) fun embeddedCredentialsAreRejected() {
        validateWebcamMediaUrl("https://user:secret@example.com/camera.jpg")
    }
}
