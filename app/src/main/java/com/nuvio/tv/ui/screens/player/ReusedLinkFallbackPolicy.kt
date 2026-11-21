package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import java.net.SocketTimeoutException
import com.nuvio.tv.core.util.findCause

internal object ReusedLinkFallbackPolicy {
    fun shouldSearchAgain(
        error: PlaybackException,
        fromReusedLink: Boolean,
        firstFrameRendered: Boolean,
        alreadySearched: Boolean,
        torboxRefreshPending: Boolean
    ): Boolean {
        val status = error.findInvalidResponseCodeException()?.responseCode
        val timeout = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.findCause<SocketTimeoutException>() != null
        return fromReusedLink && !firstFrameRendered && !alreadySearched && !torboxRefreshPending &&
            (status in setOf(401, 403, 404, 410) || timeout)
    }
}
