package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import java.net.SocketTimeoutException
import com.nuvio.tv.core.util.findCause

internal object WarmUrlFallbackPolicy {
    fun shouldFallBack(error: PlaybackException, wasSubstituted: Boolean, firstFrameRendered: Boolean, alreadyFellBack: Boolean): Boolean {
        if (!wasSubstituted || firstFrameRendered || alreadyFellBack) return false
        val status = error.findInvalidResponseCodeException()?.responseCode
        if (status in setOf(401, 403, 404, 410)) return true
        return error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.findCause<SocketTimeoutException>() != null
    }
}
