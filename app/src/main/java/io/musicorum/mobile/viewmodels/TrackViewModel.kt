package io.musicorum.mobile.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.musicorum.mobile.ktor.endpoints.TrackEndpoint
import io.musicorum.mobile.ktor.endpoints.musicorum.MusicorumAlbumEndpoint
import io.musicorum.mobile.ktor.endpoints.musicorum.MusicorumArtistEndpoint
import io.musicorum.mobile.ktor.endpoints.musicorum.MusicorumTrackEndpoint
import io.musicorum.mobile.repositories.LocalUserRepository
import io.musicorum.mobile.repositories.TrackDetailsCache
import io.musicorum.mobile.repositories.TrackDetailsCacheEntry
import io.musicorum.mobile.repositories.TrackDetailsCacheKey
import io.musicorum.mobile.serialization.Image
import io.musicorum.mobile.serialization.SimilarTrack
import io.musicorum.mobile.serialization.entities.Album
import io.musicorum.mobile.serialization.entities.Track
import io.musicorum.mobile.serialization.musicorum.TrackResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TrackLoadState {
    LOADING,
    CONTENT,
    ERROR
}

@HiltViewModel
class TrackViewModel @Inject constructor(
    application: Application,
    private val detailsCache: TrackDetailsCache
) : AndroidViewModel(application) {
    val track by lazy { MutableLiveData<Track>() }
    val similar by lazy { MutableLiveData<SimilarTrack>() }
    val loadState by lazy { MutableLiveData(TrackLoadState.LOADING) }
    private val ctx = application
    private var currentCacheKey: TrackDetailsCacheKey? = null
    private var trackFetchJob: Job? = null

    fun fetchTrack(
        trackName: String,
        artist: String,
        autoCorrect: Boolean?
    ) {
        trackFetchJob?.cancel()
        trackFetchJob = viewModelScope.launch {
            try {
                val user = LocalUserRepository(ctx).getUser()
                val cacheKey = TrackDetailsCacheKey.create(user.username, artist, trackName)
                currentCacheKey = cacheKey
                val cached = detailsCache.get(cacheKey)

                if (cached == null) {
                    track.value = null
                    similar.value = null
                    loadState.value = TrackLoadState.LOADING
                } else {
                    publishCached(cached)
                    if (detailsCache.isTrackFresh(cached)) {
                        return@launch
                    }
                }

                val response = TrackEndpoint.getTrack(
                    trackName = trackName,
                    artist = artist,
                    username = user.username,
                    autoCorrect = autoCorrect,
                    refresh = cached != null
                )
                if (response == null) {
                    showErrorWithoutDiscarding(cached)
                    return@launch
                }

                val refreshArtwork = cached == null || !detailsCache.isArtworkFresh(cached)
                val refreshedTrack = if (refreshArtwork) {
                    enrichArtwork(response.track, cached?.track)
                } else {
                    mergeArtwork(response.track, cached.track)
                }
                if (currentCacheKey != cacheKey) {
                    return@launch
                }

                detailsCache.putTrack(cacheKey, refreshedTrack, refreshArtwork)
                publishTrack(refreshedTrack)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                val cached = currentCacheKey?.let(detailsCache::get)
                showErrorWithoutDiscarding(cached)
            }
        }
    }

    suspend fun fetchSimilar(baseTrack: Track, limit: Int?, autoCorrect: Boolean?) {
        val cacheKey = currentCacheKey ?: return
        val cached = detailsCache.get(cacheKey)
        if (cached != null && detailsCache.isSimilarFresh(cached)) {
            similar.value = cached.similar
            return
        }

        try {
            val response = TrackEndpoint.fetchSimilar(baseTrack, limit, autoCorrect) ?: return
            val tracks = response.similarTracks.tracks
            if (tracks.isEmpty()) {
                return
            }

            val resources = MusicorumTrackEndpoint.fetchTracks(tracks)
            tracks.forEachIndexed { index, relatedTrack ->
                resources.getOrNull(index)?.bestAvailableImageUrl()?.let { imageUrl ->
                    relatedTrack.bestImageUrl = imageUrl
                }
            }
            if (currentCacheKey != cacheKey) {
                return
            }

            detailsCache.putSimilar(cacheKey, response)
            similar.value = response
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            cached?.similar?.let { similar.value = it }
        }
    }

    fun updateFavoritePreference(track: Track, currentlyLoved: Boolean) {
        viewModelScope.launch {
            TrackEndpoint.updateFavoritePreference(track, currentlyLoved, ctx)
            val updatedTrack = track.copy(_loved = (!currentlyLoved).toString())
            updatedTrack.bestImageUrl = track.bestImageUrl
            currentCacheKey?.let { detailsCache.updateTrack(it, updatedTrack) }
        }
    }

    private fun publishCached(cached: TrackDetailsCacheEntry) {
        similar.value = cached.similar
        publishTrack(cached.track)
    }

    private fun publishTrack(value: Track) {
        track.value = value
        loadState.value = TrackLoadState.CONTENT
    }

    private fun showErrorWithoutDiscarding(cached: TrackDetailsCacheEntry?) {
        if (cached == null) {
            loadState.value = TrackLoadState.ERROR
        } else {
            loadState.value = TrackLoadState.CONTENT
        }
    }

    private suspend fun enrichArtwork(track: Track, cachedTrack: Track?): Track {
        if (cachedTrack != null) {
            mergeArtwork(track, cachedTrack)
        }

        val trackResource = optionalRequest {
            MusicorumTrackEndpoint.fetchTracks(listOf(track)).firstOrNull()
        }
        trackResource?.bestAvailableImageUrl()?.let { imageUrl ->
            track.album = Album(
                name = trackResource.album,
                images = listOf(Image("unknown", imageUrl)),
                artist = track.artist.name
            )
        }

        val artistResource = optionalRequest {
            MusicorumArtistEndpoint.fetchArtist(listOf(track.artist)).firstOrNull()
        }
        artistResource?.bestAvailableImageUrl()?.let { imageUrl ->
            track.artist.bestImageUrl = imageUrl
        }

        val album = track.album
        if (album != null) {
            val albumResource = optionalRequest {
                MusicorumAlbumEndpoint.fetchAlbums(listOf(album)).firstOrNull()
            }
            albumResource?.bestAvailableImageUrl()?.let { imageUrl ->
                album.bestImageUrl = imageUrl
            }
        }
        return track
    }

    private fun mergeArtwork(track: Track, cachedTrack: Track): Track {
        track.bestImageUrl = cachedTrack.bestImageUrl

        val cachedArtist = cachedTrack.artist
        track.artist = track.artist.copy(
            images = cachedArtist.images ?: track.artist.images
        ).also { artist ->
            artist.bestImageUrl = cachedArtist.bestImageUrl
        }

        val cachedAlbum = cachedTrack.album
        if (cachedAlbum != null) {
            val refreshedAlbum = track.album
            track.album = if (refreshedAlbum == null) {
                cachedAlbum.copy().also { album ->
                    album.bestImageUrl = cachedAlbum.bestImageUrl
                }
            } else {
                refreshedAlbum.copy(
                    images = cachedAlbum.images ?: refreshedAlbum.images
                ).also { album ->
                    album.bestImageUrl = cachedAlbum.bestImageUrl
                }
            }
        }
        return track
    }

    private suspend fun <T> optionalRequest(block: suspend () -> T): T? {
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}

private fun TrackResponse.bestAvailableImageUrl(): String? {
    return bestResource?.bestImageUrl ?: resources.firstOrNull()?.bestImageUrl
}
