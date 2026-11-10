package com.example.earthexplorer.content

import android.view.MotionEvent
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.input.pointer.position3d

internal data class CapitalHoverHit(val entityId: Long, val position: Offset3D, val origin: Offset3D)

/** The regular spatial gesture detector starts at press, so hover needs its own event loop. */
internal suspend fun PointerInputScope.detectCapitalHover(
    onHover: (CapitalHoverHit?) -> Unit,
) {
    try {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pointer = event.changes.firstOrNull()
                if (event.type == PointerEventType.Exit || pointer == null || pointer.pressed) {
                    onHover(null)
                } else if (event.type == PointerEventType.Enter || event.type == PointerEventType.Move) {
                    onHover(PicoHoverMetadata.read(event, pointer.position3d()))
                }
            }
        }
    } finally {
        onHover(null)
    }
}

/** PICO exposes these OS event fields at runtime but does not ship their Android compile stubs. */
internal object PicoHoverMetadata {
    private class Access {
        val type = Class.forName("android.view.SpacePointerInfo")
        val constructor = type.getConstructor()
        val motionEvent = PointerEvent::class.java.getMethod("getMotionEvent")
        val read = Class.forName("android.view.SpaceEventUtils").getMethod(
            "getSpacePointerInfo", MotionEvent::class.java, Int::class.javaPrimitiveType, type)
        val entity = type.getField("entityUUID")
        val x = type.getField("inputDevicePositionX")
        val y = type.getField("inputDevicePositionY")
        val z = type.getField("inputDevicePositionZ")
    }
    private val access = try { Access() } catch (_: ReflectiveOperationException) { null }
    private var runtimeFailed = false
    val available: Boolean get() = access != null && !runtimeFailed

    fun read(event: PointerEvent, position: Offset3D): CapitalHoverHit? {
        val api = access?.takeUnless { runtimeFailed } ?: return null
        return try {
            val motion = api.motionEvent.invoke(event) as? MotionEvent ?: return null
            read(motion, position)
        } catch (_: ReflectiveOperationException) { runtimeFailed = true; null }
    }

    fun read(motion: MotionEvent, position: Offset3D): CapitalHoverHit? {
        val api = access?.takeUnless { runtimeFailed } ?: return null
        return try {
            if (motion.pointerCount == 0) return null
            val info = api.constructor.newInstance()
            api.read.invoke(null, motion, 0, info)
            val entityId = api.entity.getLong(info)
            if (entityId <= 0) return null
            CapitalHoverHit(entityId, position,
                Offset3D(api.x.getFloat(info), api.y.getFloat(info), api.z.getFloat(info)))
        } catch (_: ReflectiveOperationException) { runtimeFailed = true; null }
    }
}
