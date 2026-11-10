package com.example.earthexplorer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.earthexplorer.data.FeatureLayer
import com.example.earthexplorer.data.WebcamCatalogParser
import com.example.earthexplorer.data.WebcamMediaPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebcamCatalogTest {
    @Test
    fun tflKeepsPublishedCoordinatesAndLabelsClipsHonestly() {
        val camera = WebcamCatalogParser.parseTfl(JSONArray().put(tflRow()).toString()).single()
        assertEquals("tfl-00001.06501", camera.id)
        assertEquals(51.5011, camera.latitude, 0.0000001)
        assertEquals(-0.12628, camera.longitude, 0.0000001)
        assertEquals("Parliament Square", camera.title)
        assertTrue(camera.videoUrl!!.endsWith("00001.06501.mp4"))
        val feature = camera.toFeature(1234L)
        assertEquals(FeatureLayer.WEBCAMS, feature.layer)
        assertEquals(1234L, feature.observedAtMillis)
        assertTrue(feature.details.any { it.value.contains("not image capture time") })
    }

    @Test
    fun tflRejectsOfflineInvalidCoordinatesAndUntrustedMedia() {
        val cases = listOf(
            tflRow(available = "false"),
            tflRow(latitude = 0.0),
            tflRow(image = "https://s3-eu-west-1.amazonaws.com.evil.test/jamcams.tfl.gov.uk/00001.06501.jpg"),
            tflRow(image = "https://s3-eu-west-1.amazonaws.com/other-bucket/00001.06501.jpg"),
            tflRow(image = "https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/../00001.06501.jpg"),
            tflRow(image = "http://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00001.06501.jpg"),
            tflRow(image = "https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00001.06501.jpg?redirect=elsewhere"),
        )
        for (row in cases) assertTrue(WebcamCatalogParser.parseTfl(JSONArray().put(row).toString()).isEmpty())
    }

    @Test
    fun badTflVideoFallsBackToLegitimateSnapshotAndDuplicatesStayUnique() {
        val row = tflRow(video = "https://untrusted.example/video.mp4")
        val cameras = WebcamCatalogParser.parseTfl(JSONArray().put(row).put(row).toString())
        assertEquals(1, cameras.size)
        assertNull(cameras.single().videoUrl)
    }

    @Test
    fun austinUsesGeoJsonLongitudeLatitudeOrderAndRejectsDisabledCamera() {
        val camera = WebcamCatalogParser.parseAustin(JSONArray().put(austinRow()).toString()).single()
        assertEquals("austin-133", camera.id)
        assertEquals(30.268066, camera.latitude, 0.0000001)
        assertEquals(-97.742813, camera.longitude, 0.0000001)
        assertNull(camera.videoUrl)
        assertTrue(WebcamCatalogParser.parseAustin(JSONArray().put(austinRow().put("camera_status", "REMOVED")).toString()).isEmpty())
    }

    @Test
    fun austinRejectsSwappedCoordinatesUntrustedMediaAndUnknownExamples() {
        val swapped = austinRow().put("location", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(30.268066).put(-97.742813)))
        val wrongImage = austinRow().put("screenshot_address", "https://cctv.austinmobility.io/image/135.jpg")
        val unknown = austinRow().put("camera_id", "999999")
        val input = JSONArray().put(swapped).put(wrongImage).put(unknown)
        assertTrue(WebcamCatalogParser.parseAustin(input.toString()).isEmpty())
    }

    @Test
    fun mediaPlayerAllowlistRejectsLookalikeHostsCredentialsAndTraversal() {
        assertTrue(WebcamMediaPolicy.isAllowedUrl("https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00001.06501.mp4"))
        assertTrue(WebcamMediaPolicy.isAllowedUrl("https://cctv.austinmobility.io/image/133.jpg"))
        val rejected = listOf(
            "https://cctv.austinmobility.io.evil.test/image/133.jpg",
            "https://evil@cctv.austinmobility.io/image/133.jpg",
            "https://cctv.austinmobility.io:444/image/133.jpg",
            "https://cctv.austinmobility.io/image/%2e%2e/133.jpg",
            "file:///image/133.jpg",
            "https://s3-eu-west-1.amazonaws.com/other-bucket/00001.06501.mp4",
        )
        for (url in rejected) assertTrue("Rejected $url", !WebcamMediaPolicy.isAllowedUrl(url))
    }

    private fun tflRow(
        available: String = "true",
        latitude: Double = 51.5011,
        image: String = "https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00001.06501.jpg",
        video: String = "https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00001.06501.mp4",
    ): JSONObject = JSONObject()
        .put("id", "JamCams_00001.06501")
        .put("commonName", "Parliament Square")
        .put("lat", latitude).put("lon", -0.12628)
        .put("additionalProperties", JSONArray()
            .put(JSONObject().put("key", "available").put("value", available))
            .put(JSONObject().put("key", "imageUrl").put("value", image))
            .put(JSONObject().put("key", "videoUrl").put("value", video)))

    private fun austinRow(): JSONObject = JSONObject()
        .put("camera_id", "133").put("camera_status", "TURNED_ON")
        .put("location_name", " 6TH ST / CONGRESS AVE")
        .put("screenshot_address", "https://cctv.austinmobility.io/image/133.jpg")
        .put("location", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(-97.742813).put(30.268066)))
}
