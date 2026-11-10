package com.example.earthexplorer.content

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.earthexplorer.data.WebcamMediaPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val WEBCAM_REFRESH_MILLIS = 60_000L
private const val MAX_CAMERA_IMAGE_BYTES = 8 * 1024 * 1024

internal data class WebcamMediaState(
    val loading: Boolean = true,
    val sourceUpdatedAtMillis: Long? = null,
    val checkedAtMillis: Long? = null,
    val error: String? = null,
)

internal data class WebcamSnapshot(val bitmap: Bitmap, val sourceUpdatedAtMillis: Long?)
private data class WebcamClip(val file: File, val sourceUpdatedAtMillis: Long?, val checkedAtMillis: Long)

@Composable
internal fun NativeWebcamVideo(url: String, onState: (WebcamMediaState) -> Unit, onFailure: (String) -> Unit) {
    val latestState = rememberUpdatedState(onState)
    val latestFailure = rememberUpdatedState(onFailure)
    val cacheDirectory = LocalContext.current.cacheDir
    val player = remember(url, cacheDirectory) {
        WebcamVideoPlayer(url, cacheDirectory, { latestState.value(it) }, { latestFailure.value(it) })
    }
    DisposableEffect(player) { onDispose(player::destroy) }
    AndroidView(factory = { context -> TextureView(context).apply { player.attach(this) } },
        modifier = Modifier.fillMaxSize().clipToBounds())
}

/** A clipped Android texture keeps camera frames inside the planar window's media box. */
private class WebcamVideoPlayer(
    private val url: String,
    private val cacheDirectory: File,
    private val onState: (WebcamMediaState) -> Unit,
    private val onFailure: (String) -> Unit,
) : TextureView.SurfaceTextureListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var polling: Job? = null
    private var view: TextureView? = null
    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var clipFile: File? = null
    private var destroyed = false
    private var state = WebcamMediaState()
    private var videoWidth = 16
    private var videoHeight = 9

    fun attach(textureView: TextureView) {
        if (destroyed) return
        view = textureView
        textureView.isOpaque = false
        textureView.surfaceTextureListener = this
        if (textureView.isAvailable) textureView.surfaceTexture?.let {
            onSurfaceTextureAvailable(it, textureView.width, textureView.height)
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (destroyed) return
        polling?.cancel()
        polling = null
        releasePlayer()
        clipFile?.delete()
        clipFile = null
        surface?.release()
        surface = Surface(texture)
        fitVideo(videoWidth, videoHeight)
        startPolling()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        fitVideo(videoWidth, videoHeight)
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        polling?.cancel()
        polling = null
        releasePlayer()
        clipFile?.delete()
        clipFile = null
        surface?.release()
        surface = null
        return true
    }

    private fun startPolling() {
        if (destroyed || polling != null) return
        try {
            validateWebcamMediaUrl(url)
            polling = scope.launch {
                while (isActive && !destroyed) {
                    var unclaimedClip: WebcamClip? = null
                    try {
                        // Retain ownership across withContext's cancellable handoff to the main thread.
                        withContext(Dispatchers.IO) { unclaimedClip = fetchWebcamClip(url, cacheDirectory) }
                        val clip = unclaimedClip
                        if (clip != null && !destroyed) {
                            playLatestClip(clip)
                            unclaimedClip = null
                        }
                    } catch (_: IOException) {
                        fail("Video unavailable. Showing the provider's latest camera image instead.")
                    } finally { unclaimedClip?.file?.delete() }
                    delay(WEBCAM_REFRESH_MILLIS)
                }
            }
        } catch (_: IOException) {
            fail("Video unavailable. Showing the provider's latest camera image instead.")
        } catch (_: IllegalStateException) {
            fail("Video playback unavailable. Showing the latest camera image instead.")
        } catch (_: IllegalArgumentException) {
            fail("Video format unavailable. Showing the latest camera image instead.")
        }
    }

    private fun playLatestClip(clip: WebcamClip) {
        if (player != null && !state.loading && clip.sourceUpdatedAtMillis != null &&
            clip.sourceUpdatedAtMillis == state.sourceUpdatedAtMillis) {
            clip.file.delete()
            state = state.copy(checkedAtMillis = clip.checkedAtMillis)
            onState(state)
            return
        }
        releasePlayer()
        clipFile?.delete()
        clipFile = clip.file
        if (destroyed) { clip.file.delete(); return }
        state = WebcamMediaState(sourceUpdatedAtMillis = clip.sourceUpdatedAtMillis, checkedAtMillis = clip.checkedAtMillis)
        onState(state)
        try {
            val next = MediaPlayer()
            player = next
            next.setVolume(0f, 0f)
            next.isLooping = true
            next.setOnPreparedListener { prepared ->
                if (destroyed || player !== prepared) return@setOnPreparedListener
                try {
                    prepared.setSurface(surface)
                    fitVideo(prepared.videoWidth, prepared.videoHeight)
                    prepared.start()
                    state = state.copy(loading = false)
                    onState(state)
                    Log.i("WebcamMedia", "Latest provider clip prepared: ${prepared.videoWidth}×${prepared.videoHeight}")
                } catch (_: IllegalStateException) {
                    fail("Video playback unavailable. Showing the latest camera image instead.")
                }
            }
            next.setOnVideoSizeChangedListener { current, videoWidth, videoHeight ->
                if (!destroyed && player === current) fitVideo(videoWidth, videoHeight)
            }
            next.setOnInfoListener { current, what, _ ->
                if (!destroyed && player === current && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)
                    Log.i("WebcamMedia", "Camera video is rendering inside the planar TextureView")
                false
            }
            next.setOnErrorListener { current, what, extra ->
                if (!destroyed && player === current) {
                    Log.w("WebcamMedia", "Camera video unavailable ($what/$extra)")
                    fail("Video unavailable. Showing the provider's latest camera image instead.")
                }
                true
            }
            // All network IO is complete before the main-thread player opens a bounded local clip.
            next.setDataSource(clip.file.absolutePath)
            next.prepareAsync()
            scope.launch {
                delay(20_000)
                if (player === next && state.loading) fail("Video timed out. Showing the latest camera image instead.")
            }
        } catch (_: IOException) {
            fail("Video unavailable. Showing the provider's latest camera image instead.")
        } catch (_: IllegalStateException) {
            fail("Video playback interrupted. Showing the latest camera image instead.")
        } catch (_: IllegalArgumentException) {
            fail("Video format unavailable. Showing the latest camera image instead.")
        }
    }

    private fun fitVideo(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0 || destroyed) return
        this.videoWidth = videoWidth
        this.videoHeight = videoHeight
        val textureView = view ?: return
        val width = textureView.width.toFloat()
        val height = textureView.height.toFloat()
        if (width <= 0f || height <= 0f) return
        val (fitWidth, fitHeight) = fitWebcamVideo(width, height, videoWidth, videoHeight)
        textureView.setTransform(Matrix().apply {
            setScale(fitWidth / width, fitHeight / height, width / 2f, height / 2f)
        })
        textureView.clipBounds = Rect(0, 0, textureView.width, textureView.height)
        Log.i("WebcamMedia", "Clipped video viewport ${textureView.width}×${textureView.height}px")
    }

    private fun fail(message: String) {
        if (destroyed) return
        releasePlayer()
        clipFile?.delete()
        clipFile = null
        scope.cancel()
        onFailure(message)
    }

    private fun releasePlayer() {
        val previous = player
        player = null
        previous?.apply {
            setOnPreparedListener(null)
            setOnVideoSizeChangedListener(null)
            setOnErrorListener(null)
            setOnInfoListener(null)
            release()
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        scope.cancel()
        // The player must release its surface before TextureView releases its texture.
        releasePlayer()
        clipFile?.delete()
        clipFile = null
        surface?.release()
        surface = null
        view?.surfaceTextureListener = null
        view = null
    }
}

internal fun fitWebcamVideo(width: Float, height: Float, videoWidth: Int, videoHeight: Int): Pair<Float, Float> {
    val aspect = videoWidth.toFloat() / videoHeight.coerceAtLeast(1)
    return if (width / height > aspect) height * aspect to height else width to width / aspect
}

internal fun validateWebcamMediaUrl(value: String): URI {
    val uri = try { URI(value) } catch (invalid: URISyntaxException) {
        throw IOException("Invalid camera address", invalid)
    }
    if (!WebcamMediaPolicy.isAllowedUrl(value))
        throw IOException("Camera address is not an approved public provider endpoint")
    return uri
}

private fun webcamConnection(url: String): HttpURLConnection =
    (validateWebcamMediaUrl(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 12_000
        readTimeout = 12_000
        instanceFollowRedirects = false
        useCaches = false
        setRequestProperty("User-Agent", "EarthExplorer-PICO/1.0")
        setRequestProperty("Cache-Control", "no-cache")
    }

private suspend fun fetchWebcamClip(url: String, cacheDirectory: File): WebcamClip {
    val connection = webcamConnection(url)
    val destination = File.createTempFile("webcam-", ".mp4", cacheDirectory)
    var complete = false
    try {
        currentCoroutineContext().ensureActive()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Camera clip unavailable")
        if (connection.contentLengthLong > MAX_CAMERA_IMAGE_BYTES) throw IOException("Camera clip exceeds size limit")
        destination.outputStream().use { output ->
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var received = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    if (received > MAX_CAMERA_IMAGE_BYTES) throw IOException("Camera clip exceeds size limit")
                    output.write(buffer, 0, count)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        complete = true
        return WebcamClip(destination, connection.lastModified.takeIf { it > 0 }, System.currentTimeMillis())
    } finally {
        connection.disconnect()
        if (!complete) destination.delete()
    }
}

internal suspend fun fetchWebcamSnapshot(url: String): WebcamSnapshot {
    val connection = webcamConnection(url)
    try {
        connection.setRequestProperty("Accept", "image/jpeg,image/png")
        currentCoroutineContext().ensureActive()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Camera image unavailable")
        if (connection.contentLengthLong > MAX_CAMERA_IMAGE_BYTES) throw IOException("Camera image exceeds size limit")
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_CAMERA_IMAGE_BYTES) throw IOException("Camera image exceeds size limit")
                output.write(buffer, 0, count)
            }
        }
        val bytes = output.toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 8192 || bounds.outHeight > 8192)
            throw IOException("Camera did not provide a supported image")
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1280) options.inSampleSize *= 2
        currentCoroutineContext().ensureActive()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("Camera image could not be decoded")
        return WebcamSnapshot(bitmap, connection.lastModified.takeIf { it > 0 })
    } finally { connection.disconnect() }
}
