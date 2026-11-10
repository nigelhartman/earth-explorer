package com.example.earthexplorer.content

import com.example.earthexplorer.data.GlobeFeature
import com.example.earthexplorer.data.SolarSnapshot
import com.example.earthexplorer.data.toFeature
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val SUN_DISTANCE = 1.30f

/** Rotates one reusable night cap and terminator with the current solar direction. */
internal class SolarOverlay(parent: Entity, private val radius: Float) {
    private val root = Entity()
    private val resources = mutableListOf<Resource>()
    private var destroyed = false
    private var snapshotFeature: GlobeFeature? = null
    private var visible = false
    private var sunQuaternion = Quat(0f, 0f, 0f, 1f)
    private var globeOrientation = Quat(0f, 0f, 0f, 1f)
    private var label: ModelEntity? = null

    val feature: GlobeFeature?
        get() = snapshotFeature.takeIf { visible && !destroyed }

    init {
        require(radius.isFinite() && radius > 0f) { "Solar overlay radius must be positive" }
        try {
            root.setName("SunAndTerminator")
            root.enabled = false
            val shadow = own(UnlitMaterial.create(BlendingMode.FADE)).apply {
                setBaseColor(Color4(0.004f, 0.008f, 0.02f, 0.82f))
                setCullingMode(MaterialCullingMode.BACK)
            }
            val gold = own(UnlitMaterial.create(BlendingMode.OPAQUE)).apply {
                setBaseColor(Color4(1f, 0.79f, 0.13f, 1f))
                setCullingMode(MaterialCullingMode.NONE)
            }
            root.addChild(ModelEntity(own(nightHemisphere(radius * 1.002f)), shadow).apply {
                setName("SolarNightHemisphere")
            })
            root.addChild(ModelEntity(own(terminatorTube(radius * 1.007f, radius * 0.0025f)), gold).apply {
                setName("SolarTerminator")
            })
            // The Sun floats well clear of the globe on the subsolar axis, with a soft corona and a name.
            val sunDistance = radius * SUN_DISTANCE
            val markerMesh = own(MeshResource.createSphere(radius * 0.055f))
            val hitShape = own(ShapeResource.createSphere(radius * 0.09f))
            val physics = own(PhysicsMaterialResource())
            root.addChild(ModelEntity(markerMesh, gold).apply {
                setName("feature:sun:subsolar")
                components[TransformComponent::class.java]?.setPosition(Vector3(0f, sunDistance, 0f))
                components.set(CollisionComponent(listOf(hitShape), physics))
                components.set(InteractableComponent())
            })
            listOf(0.085f to 0.32f, 0.13f to 0.14f, 0.19f to 0.06f).forEach { (size, alpha) ->
                val corona = own(UnlitMaterial.create(BlendingMode.FADE)).apply {
                    setBaseColor(linearColor(0xFFD166, alpha)); setCullingMode(MaterialCullingMode.FRONT); setDepthWrite(false)
                }
                root.addChild(ModelEntity(own(MeshResource.createSphere(radius * size)), corona).apply {
                    setName("SunCorona")
                    components[TransformComponent::class.java]?.setPosition(Vector3(0f, sunDistance, 0f))
                })
            }
            label = createLabel().also { text ->
                text.components[TransformComponent::class.java]?.setPosition(Vector3(0f, sunDistance + radius * 0.16f, 0f))
                root.addChild(text)
            }
            parent.addChild(root)
        } catch (error: Exception) {
            root.destroy()
            resources.asReversed().forEach { it.close() }
            resources.clear()
            destroyed = true
            throw error
        }
    }

    fun update(snapshot: SolarSnapshot) {
        if (destroyed) return
        val sun = snapshot.direction
        // The reusable mesh faces +Y at the Sun and -Y at the center of the night side.
        // q = normalize(cross(+Y, sun), 1 + dot(+Y, sun)); solar declination never reaches -90°.
        val denominator = sqrt(2.0 * (1.0 + sun.y))
        sunQuaternion = Quat(
            (sun.z / denominator).toFloat(), 0f, (-sun.x / denominator).toFloat(),
            ((1.0 + sun.y) / denominator).toFloat(),
        )
        root.components[TransformComponent::class.java]?.setQuaternion(sunQuaternion)
        snapshotFeature = snapshot.toFeature()
        orientLabel()
    }

    /** The name always faces the viewer, whatever the globe and solar rotations are. */
    fun setOrientation(orientation: Quat) {
        globeOrientation = orientation
        orientLabel()
    }

    private fun orientLabel() {
        label?.components?.get(TransformComponent::class.java)?.setQuaternion(
            quaternionMultiply(quaternionConjugate(sunQuaternion), quaternionConjugate(globeOrientation)))
    }

    private fun createLabel(): ModelEntity {
        val bitmap = Bitmap.createBitmap(512, 128, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText("SUN", 256f, 92f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(255, 209, 102)
            textAlign = Paint.Align.CENTER; textSize = 82f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.28f
            setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
        })
        val texture = try { own(TextureResource.create(bitmap)) } finally { bitmap.recycle() }
        val material = own(UnlitMaterial.create(BlendingMode.FADE)).apply {
            setBaseColorTexture(texture); setCullingMode(MaterialCullingMode.NONE)
        }
        return ModelEntity(own(MeshResource.createPlane(radius * 0.30f, radius * 0.075f, 0.001f)), material)
            .apply { setName("SunLabel") }
    }

    fun setVisible(value: Boolean) {
        if (destroyed) return
        visible = value
        root.enabled = value
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        snapshotFeature = null
        root.destroy()
        resources.asReversed().forEach { it.close() }
        resources.clear()
    }

    private fun <T : Resource> own(resource: T): T = resource.also {
        it.toGlobal()
        resources += it
    }

    private fun nightHemisphere(shellRadius: Float): MeshResource {
        val rings = 32
        val segments = 180
        val positions = ArrayList<Vector3>((rings + 1) * (segments + 1))
        val normals = ArrayList<Vector3>(positions.size)
        val indices = ArrayList<Int>(rings * segments * 6)
        for (ring in 0..rings) {
            val angle = ring * PI / (2.0 * rings)
            for (segment in 0..segments) {
                val azimuth = segment * 2.0 * PI / segments
                val x = (sin(angle) * cos(azimuth)).toFloat()
                val y = -cos(angle).toFloat()
                val z = (sin(angle) * sin(azimuth)).toFloat()
                normals += Vector3(x, y, z)
                positions += Vector3(x * shellRadius, y * shellRadius, z * shellRadius)
            }
        }
        for (ring in 0 until rings) for (segment in 0 until segments) {
            val first = ring * (segments + 1) + segment
            val next = first + segments + 1
            indices += listOf(first, next, first + 1, first + 1, next, next + 1)
        }
        return MeshResource.createWithMeshModel(
            MeshModel(positions, indices, normals = normals),
            BoundingBox(Vector3(-shellRadius), Vector3(shellRadius)), "SolarNightHemisphereMesh",
        )
    }

    private fun terminatorTube(majorRadius: Float, minorRadius: Float): MeshResource {
        val segments = 180
        val sides = 6
        val positions = ArrayList<Vector3>((segments + 1) * (sides + 1))
        val normals = ArrayList<Vector3>(positions.size)
        val indices = ArrayList<Int>(segments * sides * 6)
        for (segment in 0..segments) {
            val angle = segment * 2.0 * PI / segments
            for (side in 0..sides) {
                val around = side * 2.0 * PI / sides
                val radial = majorRadius + minorRadius * cos(around)
                positions += Vector3((radial * cos(angle)).toFloat(),
                    (minorRadius * sin(around)).toFloat(), (radial * sin(angle)).toFloat())
                normals += Vector3((cos(around) * cos(angle)).toFloat(), sin(around).toFloat(),
                    (cos(around) * sin(angle)).toFloat())
            }
        }
        for (segment in 0 until segments) for (side in 0 until sides) {
            val first = segment * (sides + 1) + side
            val next = first + sides + 1
            indices += listOf(first, first + 1, next, first + 1, next + 1, next)
        }
        val extent = majorRadius + minorRadius
        return MeshResource.createWithMeshModel(
            MeshModel(positions, indices, normals = normals),
            BoundingBox(Vector3(-extent), Vector3(extent)), "SolarTerminatorMesh",
        )
    }
}
