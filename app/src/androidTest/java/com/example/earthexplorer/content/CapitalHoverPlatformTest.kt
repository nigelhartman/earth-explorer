package com.example.earthexplorer.content

import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pico.spatial.ui.geometry.Offset3D
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies OS API compatibility; actual controller hover remains an interactive device check. */
@RunWith(AndroidJUnit4::class)
class CapitalHoverPlatformTest {
    @Test fun picoRuntimeExposesHoverMetadataAndRejectsOrdinaryMouseEvents() {
        assertTrue("Installed PICO runtime must expose spatial pointer metadata", PicoHoverMetadata.available)
        val event = MotionEvent.obtain(1L, 1L, MotionEvent.ACTION_HOVER_MOVE, 20f, 30f, 0)
        try {
            assertNull(PicoHoverMetadata.read(event, Offset3D(20f, 30f, 0f)))
            assertTrue("OS metadata call must complete successfully", PicoHoverMetadata.available)
        } finally {
            event.recycle()
        }
    }
}
