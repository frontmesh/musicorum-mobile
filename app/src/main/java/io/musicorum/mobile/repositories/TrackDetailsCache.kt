package io.musicorum.mobile.repositories

import android.os.SystemClock
import io.musicorum.mobile.serialization.SimilarTrack
import io.musicorum.mobile.serialization.entities.Track
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal object TrackDetailsCachePolicy {
    const val MAX_ENTRIES = 30
    val TRACK_FRESHNESS_MILLIS: Long = TimeUnit.MINUTES.toMillis(10)
    val ARTWORK_FRESHNESS_MILLIS: Long = TimeUnit.DAYS.toMillis(7)
    val SIMILAR_FRESHNESS_MILLIS: Long = TimeUnit.HOURS.toMillis(1)
}

internal data class TrackDetailsCacheKey(
    val username: String,
    val artist: String,
    val track: String
) {
    companion object {
        fun create(username: String, artist: String, track: String): TrackDetailsCacheKey {
            return TrackDetailsCacheKey(
                username = username.normalizedCacheValue(),
                artist = artist.normalizedCacheValue(),
                track = track.normalizedCacheValue()
            )
        }
    }
}

internal data class TrackDetailsCacheEntry(
    val track: Track,
    val trackUpdatedAt: Long,
    val artworkUpdatedAt: Long?,
    val similar: SimilarTrack? = null,
    val similarUpdatedAt: Long? = null
)

@Singleton
class TrackDetailsCache internal constructor(
    private val maxEntries: Int,
    private val clock: () -> Long
) {
    @Inject
    constructor() : this(
        maxEntries = TrackDetailsCachePolicy.MAX_ENTRIES,
        clock = SystemClock::elapsedRealtime
    )

    private val entries = object : LinkedHashMap<TrackDetailsCacheKey, TrackDetailsCacheEntry>(
        maxEntries,
        CACHE_LOAD_FACTOR,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<TrackDetailsCacheKey, TrackDetailsCacheEntry>
        ): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    internal fun get(key: TrackDetailsCacheKey): TrackDetailsCacheEntry? {
        return entries[key]
    }

    internal fun isTrackFresh(entry: TrackDetailsCacheEntry): Boolean {
        return isFresh(
            entry.trackUpdatedAt,
            TrackDetailsCachePolicy.TRACK_FRESHNESS_MILLIS
        )
    }

    internal fun isArtworkFresh(entry: TrackDetailsCacheEntry): Boolean {
        val updatedAt = entry.artworkUpdatedAt ?: return false
        return isFresh(
            updatedAt,
            TrackDetailsCachePolicy.ARTWORK_FRESHNESS_MILLIS
        )
    }

    internal fun isSimilarFresh(entry: TrackDetailsCacheEntry): Boolean {
        val updatedAt = entry.similarUpdatedAt ?: return false
        return entry.similar != null && isFresh(
            updatedAt,
            TrackDetailsCachePolicy.SIMILAR_FRESHNESS_MILLIS
        )
    }

    @Synchronized
    internal fun putTrack(
        key: TrackDetailsCacheKey,
        track: Track,
        artworkRefreshed: Boolean
    ) {
        val cached = entries[key]
        val now = clock()
        entries[key] = TrackDetailsCacheEntry(
            track = track,
            trackUpdatedAt = now,
            artworkUpdatedAt = if (artworkRefreshed) now else cached?.artworkUpdatedAt,
            similar = cached?.similar,
            similarUpdatedAt = cached?.similarUpdatedAt
        )
    }

    @Synchronized
    internal fun putSimilar(key: TrackDetailsCacheKey, similar: SimilarTrack) {
        val cached = entries[key] ?: return
        entries[key] = cached.copy(
            similar = similar,
            similarUpdatedAt = clock()
        )
    }

    @Synchronized
    internal fun updateTrack(key: TrackDetailsCacheKey, track: Track) {
        val cached = entries[key] ?: return
        entries[key] = cached.copy(track = track)
    }

    internal fun isFresh(updatedAt: Long, freshnessMillis: Long): Boolean {
        return clock() - updatedAt < freshnessMillis
    }

    private companion object {
        const val CACHE_LOAD_FACTOR = 0.75f
    }
}

private fun String.normalizedCacheValue(): String {
    return trim().lowercase(Locale.ROOT)
}
