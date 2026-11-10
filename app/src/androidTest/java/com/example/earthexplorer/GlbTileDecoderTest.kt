package com.example.earthexplorer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.earthexplorer.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class GlbTileDecoderTest {
    private val frame = GeoFrame(0.0, 0.0)

    private fun fixture(badOffset: Boolean = false): ByteArray {
        val json = """{
          "asset":{"version":"2.0","copyright":"Fixture Provider"},
          "scene":0,"scenes":[{"nodes":[0]}],
          "nodes":[{"translation":[6378137,0,0],"mesh":0}],
          "meshes":[{"primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":1},"indices":2,"material":0}]}],
          "accessors":[
            {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
            {"bufferView":1,"componentType":5126,"count":3,"type":"VEC2"},
            {"bufferView":2,"componentType":5123,"count":3,"type":"SCALAR"}],
          "bufferViews":[
            {"buffer":0,"byteOffset":${if (badOffset) 400 else 0},"byteLength":36},
            {"buffer":0,"byteOffset":36,"byteLength":24},
            {"buffer":0,"byteOffset":60,"byteLength":6},
            {"buffer":0,"byteOffset":66,"byteLength":2}],
          "buffers":[{"byteLength":68}],
          "images":[{"bufferView":3,"mimeType":"image/jpeg"}],
          "textures":[{"source":0}],
          "materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}}}]
        }""".toByteArray()
        val padded = (json.size + 3) / 4 * 4
        val bytes = ByteBuffer.allocate(28 + padded + 68).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(0x46546c67).putInt(2).putInt(bytes.capacity()).putInt(padded).putInt(0x4e4f534a)
        bytes.put(json)
        repeat(padded-json.size) { bytes.put(32) }
        bytes.putInt(68).putInt(0x004e4942)
        // At longitude 0: glTF -Z is east, Y is north, X is altitude.
        floatArrayOf(0f,0f,0f, 0f,0f,-10f, 20f,10f,0f).forEach { bytes.putFloat(it) }
        floatArrayOf(0f,0f, 1f,0f, 0f,1f).forEach { bytes.putFloat(it) }
        bytes.putShort(0).putShort(1).putShort(2).put(1).put(2)
        return bytes.array()
    }

    @Test fun decodesTexturedTileIntoNativeLocalCoordinates() {
        val tile = GlbTileDecoder.decode(fixture(), "fixture", frame, DMatrix.IDENTITY)
        assertEquals("Fixture Provider", tile.copyright)
        assertEquals(1, tile.primitives.size)
        val mesh = tile.primitives.single()
        assertArrayEquals(intArrayOf(0,1,2), mesh.indices)
        assertEquals(10.0, mesh.vertices[1].x, 1e-6)
        assertEquals(20.0, mesh.vertices[2].y, 1e-6)
        assertEquals(-10.0, mesh.vertices[2].z, 1e-6)
        assertEquals(1.0, mesh.vertices[1].u, 0.0)
        assertArrayEquals(byteArrayOf(1,2), mesh.image)
    }

    @Test fun rejectsTruncatedGlb() {
        try { GlbTileDecoder.decode(fixture().copyOf(25), "fixture", frame, DMatrix.IDENTITY); fail("Expected invalid header") }
        catch (_: IOException) { }
    }

    @Test fun rejectsOutOfRangeBufferView() {
        try { GlbTileDecoder.decode(fixture(true), "fixture", frame, DMatrix.IDENTITY); fail("Expected invalid buffer view") }
        catch (_: IOException) { }
    }
}
