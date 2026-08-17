package com.watchnav.mobile.data

import com.watchnav.core.GeoPoint
import com.watchnav.mobile.speech.FishingWaypointMatcher
import com.watchnav.mobile.speech.normalizeTranscript
import com.watchnav.session.SavedWaypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FishingTranscriptionDataTest {
    @Test
    fun displayedNotesKeepManualTextAndDeduplicateTranscriptByAudioId() {
        val first = WaypointAudioTranscript("audio-1", "桥墩下有鱼口", 100L)
        val replacement = WaypointAudioTranscript("audio-1", "桥墩下方两米有鱼口", 100L)
        val values = (listOf(first).filterNot { it.audioId == replacement.audioId } + replacement)

        val profile = WaypointProfile("mark-1", "枯水期石坎", values)

        assertEquals("枯水期石坎\n录音转写：桥墩下方两米有鱼口", profile.displayedNotes)
    }

    @Test
    fun nearbyFishingEntryMatchesPrivateWaypointButNotPublicPoi() {
        val entry = MobileFishingEntry(
            id = "audio-1",
            transcript = "有鱼口",
            species = null,
            lengthCm = null,
            point = GeoPoint(30.270002, 120.093919),
            pressureHpa = 996f,
            createdAtMillis = 10_000L,
            audioFileName = "audio-1.wav",
            photos = emptyList(),
        )
        val publicPoi = SavedWaypoint("osm-node-1", "公共码头", GeoPoint(30.270002, 120.093919), 10_000L)
        val privateMark = SavedWaypoint("mark-1", "快速标记", GeoPoint(30.270010, 120.093920), 10_020L)

        assertEquals(
            privateMark,
            FishingWaypointMatcher.match(entry, listOf(publicPoi, privateMark)),
        )
        assertNull(FishingWaypointMatcher.match(entry, listOf(publicPoi)))
    }

    @Test
    fun retryDelayBacksOffAutomaticRecognition() {
        assertEquals(0L, MobileFishingLogStore.retryDelayMillis(0))
        assertEquals(15 * 60_000L, MobileFishingLogStore.retryDelayMillis(1))
        assertEquals(60 * 60_000L, MobileFishingLogStore.retryDelayMillis(2))
        assertEquals(6 * 60 * 60_000L, MobileFishingLogStore.retryDelayMillis(3))
    }

    @Test
    fun chineseTranscriptRemovesDecoderWordSpacing() {
        assertEquals("钓到大鱼一条，请标记这个点位", normalizeTranscript(" 钓 到 大鱼 一条，请 标记 这个 点位 "))
        assertEquals("水深 2.5 m 有鱼口", normalizeTranscript("水深  2.5 m  有鱼口"))
        assertEquals("这个点水下有石坎。", normalizeTranscript("<|zh|><|NEUTRAL|><|Speech|>这个点水下有石坎。"))
    }
}
