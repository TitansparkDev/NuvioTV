package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import java.net.SocketTimeoutException
import com.nuvio.tv.core.util.findCause

internal object WarmUrlFallbackPolicy {
    fun shouldFallBack(error: PlaybackException, wasSubstituted: Boolean, firstFrameRendered: Boolean, alreadyFellBack: Boolean): Boolean {
        val status = error.findInvalidResponseCodeException()?.responseCode
        val isTimeout = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.findCause<SocketTimeoutException>() != null
        return shouldFallBack(status, isTimeout, wasSubstituted, firstFrameRendered, alreadyFellBack)
    }

    internal fun shouldFallBack(status: Int?, isTimeout: Boolean, wasSubstituted: Boolean, firstFrameRendered: Boolean, alreadyFellBack: Boolean): Boolean {
        if (!wasSubstituted || firstFrameRendered || alreadyFellBack) return false
        return status in setOf(401, 403, 404, 410) || isTimeout
    }
}
