package com.example.earthexplorer.content

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Vector3

/**
 * A small native button in the volume's top-left corner that hides or shows every 2D window.
 * It lives in the 3D window so it stays reachable when the panels are gone.
 */
internal class PanelToggle(private val volumeSide: Float) {
    private val root = Entity()
    private val resources = ArrayList<Resource>()
    private var shown: ModelEntity? = null
    private var hidden: ModelEntity? = null
    private var built = false
    private var destroyed = false

    fun build(content: SpatialViewContent) {
        if (built || destroyed) return
        built = true
        fun <T : Resource> keep(resource: T): T = resource.also { it.toGlobal(); resources += it }
        val width = volumeSide * 0.17f
        val height = width * 96f / 320f
        val mesh = keep(MeshResource.createPlane(width, height, 0.001f))
        val shape = keep(ShapeResource.createBox(Vector3(width, height, 0.03f)))
        val physics = keep(PhysicsMaterialResource())
        fun material(label: String, on: Boolean): UnlitMaterial {
            val bitmap = Bitmap.createBitmap(320, 96, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.argb(215, 8, 15, 28)
            canvas.drawRoundRect(RectF(0f, 0f, 320f, 96f), 48f, 48f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.argb(if (on) 200 else 110, 79, 227, 255)
            canvas.drawRoundRect(RectF(2f, 2f, 318f, 94f), 48f, 48f, paint)
            // Three little panes: filled when the panels are shown, outlined when hidden.
            paint.strokeWidth = 4f
            for (i in 0 until 3) {
                val left = 30f + i * 26f
                val rect = RectF(left, 30f, left + 18f, 66f)
                paint.style = if (on) Paint.Style.FILL else Paint.Style.STROKE
                canvas.drawRoundRect(rect, 4f, 4f, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(234, 246, 255)
            paint.textSize = 34f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.letterSpacing = 0.12f
            canvas.drawText(label, 122f, 60f, paint)
            val texture = try { keep(TextureResource.create(bitmap)) } finally { bitmap.recycle() }
            return keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
                setBaseColorTexture(texture); setCullingMode(MaterialCullingMode.NONE)
            }
        }
        root.setName(PANELS_TOGGLE_NAME)
        root.components[TransformComponent::class.java]?.setPosition(Vector3(
            -volumeSide / 2 + width / 2 + volumeSide * 0.02f, volumeSide / 2 - height / 2 - volumeSide * 0.02f, 0f)) // Same depth plane as the city label.
        root.components.set(CollisionComponent(listOf(shape), physics))
        root.components.set(InteractableComponent())
        shown = ModelEntity(mesh, material("PANELS", true)).also { root.addChild(it) }
        hidden = ModelEntity(mesh, material("PANELS", false)).also { it.enabled = false; root.addChild(it) }
        content.addEntity(root)
    }

    fun setPanelsVisible(visible: Boolean) {
        shown?.enabled = visible
        hidden?.enabled = !visible
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        root.destroy()
        resources.asReversed().forEach { it.close() }
        resources.clear()
    }
}
