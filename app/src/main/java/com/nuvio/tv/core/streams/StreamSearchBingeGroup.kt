package com.nuvio.tv.core.streams

import com.nuvio.tv.data.local.BingeGroupCacheDataStore
import com.nuvio.tv.data.local.PlayerSettings

object StreamSearchBingeGroup {
    suspend fun resolve(playerSettings: PlayerSettings, bingeGroupCacheDataStore: BingeGroupCacheDataStore, contentId: String?): String? =
        if (playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode && playerSettings.streamAutoPlayReuseBingeGroup) {
            contentId?.let { bingeGroupCacheDataStore.get(it) }
        } else null
}
