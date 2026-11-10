package com.example.earthexplorer.content

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.example.earthexplorer.data.CapitalCity
import com.example.earthexplorer.data.CapitalCities
import com.example.earthexplorer.data.DVec3
import com.example.earthexplorer.data.FeatureGeometry
import com.example.earthexplorer.data.FeaturePickRay
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import kotlin.math.sqrt

/** Uses actual pin spheres, not the elevated input shells belonging to other map layers. */
internal fun hoveredCapital(
    ray: FeaturePickRay,
    radius: Double,
    capitals: List<CapitalCity> = CapitalCities.all,
): CapitalCity? {
    if (!radius.isFinite() || radius <= 0 || !ray.direction.length().isFinite()) return null
    val length = ray.direction.length()
    if (length < 1e-8) return null
    val direction = ray.direction * (1.0 / length)
    fun entry(center: DVec3, sphereRadius: Double): Double? {
        val offset = ray.origin - center
        val b = offset.dot(direction)
        val discriminant = b * b - offset.dot(offset) + sphereRadius * sphereRadius
        if (discriminant < 0 || !discriminant.isFinite()) return null
        val distance = -b - sqrt(discriminant)
        return distance.takeIf { it >= 0 }
    }
    val earth = entry(DVec3(0.0, 0.0, 0.0), radius) ?: Double.POSITIVE_INFINITY
    return capitals.mapNotNull { capital ->
        val point = FeatureGeometry.direction(capital.latitude, capital.longitude) * (radius * 1.012)
        val distance = entry(point, radius * 0.032) ?: return@mapNotNull null
        // Enlarged pin hit targets at the limb must not reveal cities behind the opaque Earth.
        val centerRay = FeatureGeometry.rayThrough(ray.origin, point) ?: return@mapNotNull null
        val obstruction = FeatureGeometry.pointOnEarth(centerRay, radius)
        if (obstruction != null && (obstruction - ray.origin).length() < (point - ray.origin).length() - 1e-8)
            return@mapNotNull null
        if (distance > earth) null else capital to distance
    }.minByOrNull { it.second }?.first
}

/** One reused billboard keeps 200 capital labels from creating hundreds of extra native textures. */
internal class CapitalHoverLabel(private val parent: Entity, private val radius: Float) {
    private val resources = mutableListOf<Resource>()
    private var entity: ModelEntity? = null
    private var city: CapitalCity? = null
    private var orientation = Quat(0f, 0f, 0f, 1f)

    fun show(capital: CapitalCity?) {
        if (city == capital) return
        clear()
        city = capital ?: return
        val bitmap = Bitmap.createBitmap(1024, 176, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.argb(225, 8, 15, 28)
        canvas.drawRoundRect(0f, 0f, 1024f, 176f, 32f, 32f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = android.graphics.Color.argb(170, 79, 227, 255)
        canvas.drawRoundRect(2f, 2f, 1022f, 174f, 32f, 32f, paint)
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.rgb(255, 200, 87)
        canvas.drawCircle(58f, 88f, 12f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 76f
        paint.letterSpacing = 0f
        while (paint.measureText(capital.name) > 880 && paint.textSize > 28) paint.textSize -= 2
        canvas.drawText(capital.name, 540f, 84f, paint)
        paint.color = android.graphics.Color.rgb(79, 227, 255)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 38f
        paint.letterSpacing = 0.16f
        val country = capital.country.uppercase()
        while (paint.measureText(country) > 880 && paint.textSize > 22) paint.textSize -= 2
        canvas.drawText(country, 540f, 140f, paint)
        val texture = try { keep(TextureResource.create(bitmap)) } finally { bitmap.recycle() }
        val material = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
            setBaseColorTexture(texture)
            setCullingMode(MaterialCullingMode.NONE)
        }
        entity = ModelEntity(keep(MeshResource.createPlane(radius * 0.92f, radius * 0.158f, 0.001f)), material)
            .also { it.setName("capital-hover-label"); parent.addChild(it) }
        position()
    }

    fun setOrientation(value: Quat) { orientation = value; position() }

    private fun position() {
        val selected = city ?: return
        val point = FeatureGeometry.direction(selected.latitude, selected.longitude) * (radius * 1.04)
        val inverse = Quat(-orientation.x, -orientation.y, -orientation.z, orientation.w)
        val v = Vector3(0f, radius * 0.13f, radius * 0.025f)
        val crossX = inverse.y * v.z - inverse.z * v.y
        val crossY = inverse.z * v.x - inverse.x * v.z
        val crossZ = inverse.x * v.y - inverse.y * v.x
        entity?.components?.get(TransformComponent::class.java)?.apply {
            setPosition(Vector3(
                point.x.toFloat() + v.x + 2 * (inverse.w * crossX + inverse.y * crossZ - inverse.z * crossY),
                point.y.toFloat() + v.y + 2 * (inverse.w * crossY + inverse.z * crossX - inverse.x * crossZ),
                point.z.toFloat() + v.z + 2 * (inverse.w * crossZ + inverse.x * crossY - inverse.y * crossX)))
            setQuaternion(inverse)
        }
    }

    fun clear() {
        city = null
        entity?.destroy()
        entity = null
        resources.forEach { it.close() }
        resources.clear()
    }

    private fun <T : Resource> keep(resource: T): T = resource.also { it.toGlobal(); resources += it }
}
