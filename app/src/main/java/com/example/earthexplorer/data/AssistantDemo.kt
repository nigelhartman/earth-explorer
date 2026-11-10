package com.example.earthexplorer.data

import android.os.SystemClock
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AssistantState { IDLE, LISTENING, THINKING, ANSWERING }

data class AssistantPresentation(
    val state: AssistantState = AssistantState.IDLE,
    /** The scripted question, revealed letter by letter while the button is held. */
    val transcript: String = "",
    val answer: String = "",
    val step: Int = 0,
)

/**
 * A scripted voice assistant for demonstrations. Holding the orb "listens" to one of three
 * prepared questions; releasing it runs the matching action on the real data. No microphone
 * audio is captured or sent anywhere; the transcript is the script, not recognition.
 */
object AssistantDemo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _presentation = MutableStateFlow(AssistantPresentation())
    val presentation = _presentation.asStateFlow()
    private var typing: Job? = null
    private var acting: Job? = null
    private var pressedAt = 0L
    private var latestQuake: GlobeFeature? = null

    private val questions = listOf(
        "Where was the latest earthquake?",
        "Take me there.",
        "Show me the epicenter and where people should go.",
    )

    /** The epicenter marker in the city stays hidden until the third question releases it. */
    private val _epicenterRevealed = MutableStateFlow(false)
    val epicenterRevealed = _epicenterRevealed.asStateFlow()

    fun pressStart() {
        acting?.cancel()
        typing?.cancel()
        pressedAt = SystemClock.elapsedRealtime()
        val step = _presentation.value.step
        _presentation.value = AssistantPresentation(AssistantState.LISTENING, "", "", step)
        val question = questions[step]
        typing = scope.launch {
            // A second of slack so the speaker has started talking before the transcript types out.
            delay(1_000)
            for (index in 1..question.length) {
                if (!isActive) return@launch
                _presentation.value = _presentation.value.copy(transcript = question.substring(0, index))
                delay(if (question[index - 1] == ' ') 90L else 45L)
            }
        }
    }

    fun pressEnd() {
        typing?.cancel()
        val step = _presentation.value.step
        val question = questions[step]
        if (SystemClock.elapsedRealtime() - pressedAt < 250) {
            // A tap, not a hold: keep the orb idle so a mis-press does not advance the script.
            _presentation.value = AssistantPresentation(step = step)
            return
        }
        _presentation.value = AssistantPresentation(AssistantState.THINKING, question, "", step)
        acting = scope.launch {
            val answer = when (step) {
                0 -> locateLatestEarthquake()
                1 -> diveIn()
                else -> revealEpicenter()
            }
            _presentation.value = AssistantPresentation(AssistantState.ANSWERING, question, answer, (step + 1) % questions.size)
            delay(9_000)
            if (_presentation.value.state == AssistantState.ANSWERING) {
                _presentation.value = _presentation.value.copy(state = AssistantState.IDLE)
            }
        }
    }

    fun reset() {
        acting?.cancel()
        typing?.cancel()
        latestQuake = null
        _epicenterRevealed.value = false
        EarthquakeLayerStore.setScenario(null)
        _presentation.value = AssistantPresentation()
    }

    const val SCENARIO_ID = "earthquake:scenario-san-francisco"

    /**
     * The presentation scenario: a strong event at the foot of the Transamerica Pyramid, where
     * photorealistic tiles and a recognizable landmark exist. It is a prepared event, not a catalog observation.
     */
    private fun scenario(now: Long): GlobeFeature {
        val time = now - 4 * 60_000L
        val occurred = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm:ss 'UTC'", Locale.ENGLISH)
            .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(time))
        return GlobeFeature(
            id = SCENARIO_ID, layer = FeatureLayer.EARTHQUAKES,
            latitude = 37.7946, longitude = -122.4030,
            title = "M 6.9 · San Francisco, California", subtitle = "Earthquake · $occurred",
            observedAtMillis = time,
            details = listOf(
                FeatureDetail("Magnitude", "6.9 mww"),
                FeatureDetail("Depth", "11.0 km"),
                FeatureDetail("Occurred", occurred),
                FeatureDetail("Nearest landmark", "Transamerica Pyramid · Financial District"),
                FeatureDetail("Shaking", "Severe · intensity VIII"),
            ),
            source = "Earth Explorer scenario", magnitude = 6.9, depthKilometers = 11.0,
        )
    }

    private suspend fun locateLatestEarthquake(): String {
        if (!MapLayers.earthquakesVisible.value) MapLayers.setEarthquakesVisible(true)
        delay(3_000) // "processing" before the globe turns
        val quake = scenario(System.currentTimeMillis())
        _epicenterRevealed.value = false
        EarthquakeLayerStore.setScenario(quake)
        latestQuake = quake
        delay(200)
        FeatureSelection.preview(quake)
        GlobeSelection.focusOn(quake.latitude, quake.longitude, blink = true)
        return "M 6.9 earthquake in San Francisco, California, ${ageText(quake.observedAtMillis)}. Turning the globe to the epicenter."
    }

    private suspend fun diveIn(): String {
        delay(2_800) // "processing" before the dive-in starts
        val quake = latestQuake ?: FeatureSelection.selected.value
            ?: return "Select a place first, then ask me to take you in."
        if (quake.id == SCENARIO_ID) FeatureSelection.exploreGround(quake, "San Francisco", "United States")
        else FeatureSelection.exploreGround(quake)
        return "Taking you to downtown San Francisco. Streaming photorealistic terrain now."
    }

    private suspend fun revealEpicenter(): String {
        delay(2_800) // "processing" before the epicenter is released
        // The epicenter is the streamed city's origin; snap the view back there in case it drifted.
        LocationStore.recenterCity()
        _epicenterRevealed.value = true
        return "Epicenter marked, 60 m south of the Transamerica Pyramid. Stay clear of glass facades. " +
            "Nearest assembly area: Portsmouth Square, 400 m north-east."
    }

    private fun ageText(observedAtMillis: Long): String {
        val hours = ((System.currentTimeMillis() - observedAtMillis) / 3_600_000L).coerceAtLeast(0)
        return when {
            hours < 1 -> "minutes ago"
            hours < 48 -> "$hours hours ago"
            else -> "${hours / 24} days ago"
        }
    }
}
