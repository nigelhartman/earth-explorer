package com.example.earthexplorer.content

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.example.earthexplorer.data.GeoFrame
import com.example.earthexplorer.data.ExplorerMotion
import com.example.earthexplorer.data.ExplorerMotionPhase
import com.example.earthexplorer.data.GlobeSelection
import com.example.earthexplorer.data.LocationStore
import com.example.earthexplorer.data.PanelVisibility
import com.example.earthexplorer.data.SelectedLocation
import com.pico.spatial.core.container.SpatialViewContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class ExplorerInteraction { EARTH, CITY, LOCKED }

/** Both destinations share one native volume so the Earth pieces can become the city's floor. */
internal class ExplorerScene(
    private val globe: GlobeScene,
    private val volumeSide: Float,
    private val assets: AssetManager,
) {
    private val contentReady = CompletableDeferred<SpatialViewContent>()
    private val changeMutex = Mutex()
    private val transition = SceneTransition(volumeSide)
    private val panelToggle = PanelToggle(volumeSide)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var city: CityScene? = null
    private var cityCoordinate: Pair<Double, Double>? = null
    private var cityDisplayed = false
    private var built = false
    private var destroyed = false
    var interaction = ExplorerInteraction.EARTH
        private set

    suspend fun build(content: SpatialViewContent) {
        if (built || destroyed) return
        built = true
        val earth = withContext(Dispatchers.Default) { loadBrightEarth(assets) }
        val night = withContext(Dispatchers.Default) { loadNightLights(assets) }
        try {
            globe.build(content, volumeSide * 0.33f, earth, night)
            scope.launch { globe.bootReveal() }
            // Pay the Earth-fragment upload cost while the initial globe appears, not after a tap.
            try { transition.build(content, earth) }
            catch (error: Exception) {
                Log.w("ExplorerTransition", "Earth transition prewarm unavailable: ${error.javaClass.simpleName}")
            }
        } finally {
            earth.recycle()
            night?.recycle()
        }
        globe.setIdleAllowed(true)
        try { panelToggle.build(content) }
        catch (error: Exception) { Log.w("ExplorerTransition", "Panel toggle unavailable: ${error.javaClass.simpleName}") }
        scope.launch { PanelVisibility.visible.collect(panelToggle::setPanelsVisible) }
        scope.launch {
            while (isActive) {
                transition.tick(SystemClock.elapsedRealtime())
                delay(33)
            }
        }
        currentCoroutineContext().ensureActive()
        if (!destroyed) contentReady.complete(content)
    }

    suspend fun show(location: SelectedLocation?) {
        val content = contentReady.await()
        // Effect cancellation does not join native IO. Finish cleanup before a newer destination
        // can replace the scenes.
        changeMutex.withLock {
            showLocked(content, location)
        }
    }

    private suspend fun showLocked(content: SpatialViewContent, location: SelectedLocation?) {
        if (destroyed) return
        interaction = ExplorerInteraction.LOCKED
        globe.hover(null)
        LocationStore.endPanGesture()
        try {
            if (location == null) returnToEarth()
            else openCity(content, location)
        } catch (canceled: CancellationException) {
            // Compose cancels an obsolete destination before applying the next one.
            throw canceled
        } catch (error: Exception) {
            Log.w("ExplorerTransition", "Transition unavailable: ${error.javaClass.simpleName}")
            if (location != null && city != null) {
                globe.setVisible(false)
                city?.setVisible(true)
                city?.setOverlayVisible(true)
                city?.showAllTiles()
                city?.setUndersideActive(true)
                cityDisplayed = true
                interaction = ExplorerInteraction.CITY
                ExplorerMotion.setPhase(ExplorerMotionPhase.CITY)
            } else {
                globe.setVisible(true)
                city?.destroy()
                city = null
                cityCoordinate = null
                cityDisplayed = false
                interaction = ExplorerInteraction.EARTH
                ExplorerMotion.setPhase(ExplorerMotionPhase.EARTH)
            }
        } finally {
            globe.clearTarget()
        }
    }

    private suspend fun openCity(content: SpatialViewContent, location: SelectedLocation) = coroutineScope {
        val coordinate = location.latitude to location.longitude
        if (cityCoordinate == coordinate && cityDisplayed) {
            globe.setVisible(false)
            city?.setVisible(true)
            city?.setOverlayVisible(true)
            interaction = ExplorerInteraction.CITY
            ExplorerMotion.setPhase(ExplorerMotionPhase.CITY)
            return@coroutineScope
        }
        city?.destroy()
        city = null
        cityDisplayed = false
        globe.setVisible(true)
        globe.setIdleAllowed(false)
        ExplorerMotion.setPhase(ExplorerMotionPhase.PREPARING_CITY)
        // Lock on: the reticle pulses and the globe turns the place toward the viewer.
        val aim = launch { globe.aimAt(location.latitude, location.longitude) }
        val ready = CompletableDeferred<Unit>()
        val destination = CityScene(
            GeoFrame(location.latitude, location.longitude),
            volumeSide,
            onReady = { ready.complete(Unit) },
            revealTiles = transition.ready,
        )
        city = destination
        cityCoordinate = coordinate
        destination.setVisible(transition.ready)
        destination.setOverlayVisible(false)
        destination.build(content)
        aim.join()
        currentCoroutineContext().ensureActive()
        globe.clearTarget()
        if (!transition.ready) {
            ready.await()
            globe.setVisible(false)
            destination.setVisible(true)
            destination.setOverlayVisible(true)
            destination.setUndersideActive(true)
            cityDisplayed = true
            interaction = ExplorerInteraction.CITY
            ExplorerMotion.setPhase(ExplorerMotionPhase.CITY)
            return@coroutineScope
        }
        ExplorerMotion.setPhase(ExplorerMotionPhase.TO_CITY)
        val focus = GlobeSelection.focus.value
        // The Earth dissolves into the floor grid while the tiles keep streaming behind it.
        transition.play(true, focus.latitude.toFloat(), focus.longitude.toFloat(),
            onSourceHidden = { globe.setVisible(false) },
            onDestinationVisible = { destination.allowReveals() })
        cityDisplayed = true
        interaction = ExplorerInteraction.CITY
        ExplorerMotion.setPhase(ExplorerMotionPhase.BUILDING_CITY)
        // The city label appears with the first rising tile, not after the whole view has developed.
        destination.awaitFirstTile()
        destination.setOverlayVisible(true)
        ready.await()
        destination.awaitRevealsSettled()
        currentCoroutineContext().ensureActive()
        transition.retractFloor()
        destination.setOverlayVisible(true)
        destination.setUndersideActive(true)
        ExplorerMotion.setPhase(ExplorerMotionPhase.CITY)
        Log.i("ExplorerTransition", "City ready for interaction")
    }

    private suspend fun returnToEarth() {
        val source = city
        source?.setOverlayVisible(false)
        source?.setUndersideActive(false)
        if (source != null && cityDisplayed && transition.ready) {
            ExplorerMotion.setPhase(ExplorerMotionPhase.TO_EARTH)
            val focus = GlobeSelection.focus.value
            transition.raiseFloor(focus.latitude.toFloat(), focus.longitude.toFloat())
            source.sinkTiles()
            transition.play(false, focus.latitude.toFloat(), focus.longitude.toFloat(),
                onSourceHidden = { source.setVisible(false) },
                onDestinationVisible = { globe.setVisible(true) })
        } else globe.setVisible(true)
        source?.destroy()
        city = null
        cityCoordinate = null
        cityDisplayed = false
        interaction = ExplorerInteraction.EARTH
        globe.setIdleAllowed(true)
        ExplorerMotion.setPhase(ExplorerMotionPhase.EARTH)
        Log.i("ExplorerTransition", "Earth ready for interaction")
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        interaction = ExplorerInteraction.LOCKED
        ExplorerMotion.setPhase(ExplorerMotionPhase.EARTH)
        scope.coroutineContext[Job]?.cancel()
        contentReady.cancel()
        transition.destroy()
        panelToggle.destroy()
        city?.destroy()
        city = null
        globe.destroy()
    }
}
