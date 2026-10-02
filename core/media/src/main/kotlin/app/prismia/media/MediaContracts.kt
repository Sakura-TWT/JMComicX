package app.prismia.media

import app.prismia.foundation.ContentKey

/** Common lifecycle shape for reader/player/download sessions. */
sealed interface MediaSessionState {
    data object Idle : MediaSessionState
    data object Preparing : MediaSessionState
    data class Ready(val content: ContentKey) : MediaSessionState
    data class Failed(val message: String) : MediaSessionState
}

enum class MediaKind {
    READER,
    PLAYER,
}
