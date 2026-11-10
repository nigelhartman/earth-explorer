package com.example.earthexplorer.content

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.earthexplorer.data.AssistantDemo
import com.example.earthexplorer.data.AssistantState
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
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialPointerEvent
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.sin

private const val ORB_NAME = "AssistantOrb"

/**
 * A glowing native orb in its own small volume. Pinch and hold to ask: it turns green while held,
 * violet while processing, bright while answering, then breathes cyan again. The transcript and
 * answer are drawn on floating text planes beneath it.
 */
@Composable
fun AssistantPanel() {
    val context = LocalContext.current
    val converter = LocalPhysicalLengthConverter.current
    val side = remember(converter) { converter.dpToLength(300.dp, LengthUnit.Meters) }
    val height = remember(converter) { converter.dpToLength(300.dp, LengthUnit.Meters) }
    val orb = remember(side) { AssistantOrb(side) }
    DisposableEffect(orb) { onDispose(orb::destroy) }
    LaunchedEffect(orb) {
        AssistantDemo.presentation.collect { orb.present(it.state, it.transcript, it.answer) }
    }
    LaunchedEffect(orb) {
        while (isActive) { orb.tick(SystemClock.elapsedRealtime()); delay(33) }
    }
    SpatialView(
        modifier = Modifier.fillMaxSize().pointerInput(orb) {
            detectSpatialPointerEvent(context, TargetEntity.any { it.getName() == ORB_NAME }) { events ->
                events.forEach { event ->
                    if (event.isDownEvent()) AssistantDemo.pressStart()
                    else if (event.isUpEvent()) AssistantDemo.pressEnd()
                }
                false
            }
        },
    ) { content, _ -> orb.build(content) }
}

private class AssistantOrb(private val side: Float) {
    private val root = Entity()
    private val resources = ArrayList<Resource>()
    private var core: UnlitMaterial? = null
    private var shell: UnlitMaterial? = null
    private var halos = emptyList<Pair<UnlitMaterial, Float>>()
    private var orbTransform: TransformComponent? = null
    private var ringTransform: TransformComponent? = null
    private var ringMaterial: UnlitMaterial? = null
    private var titleEntity: ModelEntity? = null
    private var lines: Pair<ModelEntity, List<Resource>>? = null
    private var state = AssistantState.IDLE
    private var stateSince = 0L
    private var rgb = 0x4FE3FF
    private var built = false
    private var destroyed = false

    fun build(content: SpatialViewContent) {
        if (built || destroyed) return
        built = true
        fun <T : Resource> keep(resource: T): T = resource.also { it.toGlobal(); resources += it }
        val radius = side * 0.20f
        // Gradient textures: a white-hot core that falls off to the accent, and a shell that runs
        // from a lighter tint at the top to a deeper one at the bottom. Tinting the base color
        // recolors both, so state changes only touch two materials.
        val coreTexture = keep(TextureResource.create(gradientBitmap(radial = true)))
        val shellTexture = keep(TextureResource.create(gradientBitmap(radial = false)))
        core = keep(UnlitMaterial.create(BlendingMode.ADD)).apply {
            setBaseColorTexture(coreTexture); setBaseColor(linearColor(0xFFFFFF, 1f)); setCullingMode(MaterialCullingMode.BACK)
        }
        shell = keep(UnlitMaterial.create(BlendingMode.ADD)).apply {
            setBaseColorTexture(shellTexture); setBaseColor(linearColor(rgb, 0.85f)); setCullingMode(MaterialCullingMode.BACK); setDepthWrite(false)
        }
        val orb = Entity().apply {
            setName(ORB_NAME)
            components[TransformComponent::class.java]?.setPosition(Vector3(0f, side * 0.08f, 0f))
            components.set(CollisionComponent(listOf(keep(ShapeResource.createSphere(radius * 2.6f))), keep(PhysicsMaterialResource())))
            components.set(InteractableComponent())
            addChild(ModelEntity(keep(MeshResource.createSphere(radius * 0.55f)), core!!))
            addChild(ModelEntity(keep(MeshResource.createSphere(radius * 0.72f)), shell!!))
            halos = listOf(1.0f to 0.55f, 1.6f to 0.22f, 2.4f to 0.08f).map { (scale, alpha) ->
                val material = keep(UnlitMaterial.create(BlendingMode.ADD)).apply {
                    setBaseColor(linearColor(rgb, alpha)); setCullingMode(MaterialCullingMode.FRONT); setDepthWrite(false)
                }
                addChild(ModelEntity(keep(MeshResource.createSphere(radius * scale)), material))
                material to alpha
            }
            ringMaterial = keep(UnlitMaterial.create(BlendingMode.ADD)).apply {
                setBaseColor(linearColor(rgb, 0.7f)); setCullingMode(MaterialCullingMode.NONE); setDepthWrite(false)
            }
            addChild(ModelEntity(keep(ringMesh(radius * 1.9f, radius * 0.045f, "assistant-ring")), ringMaterial!!).apply {
                ringTransform = components[TransformComponent::class.java]
            })
            orbTransform = components[TransformComponent::class.java]
        }
        root.addChild(orb)
        titleEntity = textPlane("AI ASSISTANT", 0xFFFFFF, 0.80f, 96f, bold = true, letterSpacing = 0.26f).also { plane ->
            plane.components[TransformComponent::class.java]?.setPosition(Vector3(0f, -side * 0.30f, 0f))
            root.addChild(plane)
        }
        content.addEntity(root)
        present(AssistantState.IDLE, "", "")
    }

    fun present(newState: AssistantState, transcript: String, answer: String) {
        if (!built || destroyed) return
        if (newState != state) { state = newState; stateSince = SystemClock.elapsedRealtime() }
        rgb = when (newState) {
            AssistantState.IDLE -> 0x4FE3FF
            AssistantState.LISTENING -> 0x4DF58A
            AssistantState.THINKING -> 0xB08CFF
            AssistantState.ANSWERING -> 0xEAF6FF
        }
        halos.forEach { (material, alpha) -> material.setBaseColor(linearColor(rgb, alpha)) }
        shell?.setBaseColor(linearColor(rgb, 0.85f))
        ringMaterial?.setBaseColor(linearColor(rgb, 0.7f))
    }

    /** Radial: white center to transparent edge. Linear: bright top to dark bottom. */
    private fun gradientBitmap(radial: Boolean): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = if (radial) RadialGradient(size / 2f, size / 2f, size / 2f,
                intArrayOf(Color.WHITE, Color.argb(220, 255, 255, 255), Color.argb(0, 255, 255, 255)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            else LinearGradient(0f, 0f, 0f, size.toFloat(),
                intArrayOf(Color.WHITE, Color.argb(255, 170, 170, 170), Color.argb(255, 40, 40, 40)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        Canvas(bitmap).drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        return bitmap
    }

    /** Breathing scale and glow; faster when listening, jittery when thinking. */
    fun tick(now: Long) {
        if (!built || destroyed) return
        val t = (now - stateSince) / 1000f
        val (rate, depth) = when (state) {
            AssistantState.IDLE -> 0.9f to 0.06f
            AssistantState.LISTENING -> 3.2f to 0.14f
            AssistantState.THINKING -> 5.5f to 0.10f
            AssistantState.ANSWERING -> 1.6f to 0.08f
        }
        val breathe = 1f + depth * sin(t * rate * 2f)
        orbTransform?.setScaleVector(Vector3(breathe))
        ringTransform?.setScaleVector(Vector3(1f + 0.3f * (0.5f + 0.5f * sin(t * rate)), 1f, 1f + 0.3f * (0.5f + 0.5f * sin(t * rate))))
        ringMaterial?.setBaseColor(linearColor(rgb, 0.75f * (1f - (0.5f + 0.5f * sin(t * rate)))))
        // Colors drift between the state accent and a neighbouring tint so the orb never sits still.
        val drift = 0.5f + 0.5f * sin(t * 0.7f)
        val tint = mixRgb(rgb, when (state) {
            AssistantState.IDLE -> 0x7CB8FF; AssistantState.LISTENING -> 0xB6FF6E
            AssistantState.THINKING -> 0xFF8CE0; AssistantState.ANSWERING -> 0xBFF4FF
        }, drift)
        halos.forEach { (material, alpha) -> material.setBaseColor(linearColor(tint, alpha * (0.8f + 0.2f * sin(t * rate * 2f)))) }
        shell?.setBaseColor(linearColor(tint, 0.85f))
    }

    private fun mixRgb(a: Int, b: Int, k: Float): Int {
        fun ch(v: Int, s: Int) = (v shr s) and 255
        fun mix(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * k).toInt().coerceIn(0, 255)
        return (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun textPlane(text: String, rgb: Int, widthFactor: Float, textSize: Float, bold: Boolean, letterSpacing: Float,
        owned: MutableList<Resource> = resources, wrap: Boolean = false): ModelEntity {
        val width = 1024; val height = if (wrap) 320 else 128
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255)
            textAlign = Paint.Align.CENTER; this.textSize = textSize
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; this.letterSpacing = letterSpacing
            setShadowLayer(8f, 0f, 0f, android.graphics.Color.argb(200, 0, 0, 0))
        }
        if (!wrap) canvas.drawText(text, width / 2f, height * 0.68f, paint)
        else {
            // Simple word wrap into at most four lines.
            val lines = ArrayList<String>()
            var current = ""
            text.split(' ').forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) > width - 80 && current.isNotEmpty()) { lines += current; current = word } else current = candidate
            }
            if (current.isNotEmpty()) lines += current
            lines.take(4).forEachIndexed { i, line -> canvas.drawText(line, width / 2f, 70f + i * 62f, paint) }
        }
        val texture = try { TextureResource.create(bitmap).also { it.toGlobal(); owned += it } } finally { bitmap.recycle() }
        val material = UnlitMaterial.create(BlendingMode.FADE).also { it.toGlobal(); owned += it }.apply {
            setBaseColorTexture(texture); setCullingMode(MaterialCullingMode.NONE); setDepthWrite(false)
        }
        val planeWidth = side * widthFactor * (width / 1024f)
        val mesh = MeshResource.createPlane(planeWidth, planeWidth * height / width, 0.001f).also { it.toGlobal(); owned += it }
        return ModelEntity(mesh, material)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        lines?.second?.forEach { it.close() }
        root.destroy()
        resources.asReversed().forEach { it.close() }
        resources.clear()
    }
}
