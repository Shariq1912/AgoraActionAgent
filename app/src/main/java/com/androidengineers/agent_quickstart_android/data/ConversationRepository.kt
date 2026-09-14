package com.androidengineers.agent_quickstart_android.data

import com.androidengineers.agent_quickstart_android.model.AgentInviteResult
import com.androidengineers.agent_quickstart_android.model.AgoraTokenBundle
import com.androidengineers.agent_quickstart_android.model.BackendHealthResult
import com.androidengineers.agent_quickstart_android.model.RenewalTokens

class ConversationRepository(
    private val api: ConversationAgoraApi = ConversationAgoraApi(),
) {
    suspend fun checkHealth(): BackendHealthResult {
        return api.checkHealth()
    }

    suspend fun requestSessionBootstrap(): AgoraTokenBundle {
        return api.requestSessionBootstrap()
    }

    suspend fun inviteAgent(
        channelName: String,
        requesterRtcUid: String,
    ): AgentInviteResult {
        return api.inviteAgent(
            channelName = channelName,
            requesterRtcUid = requesterRtcUid,
        )
    }

    suspend fun stopConversation(
        agentId: String,
        channelName: String,
    ) {
        api.stopConversation(agentId, channelName)
    }

    suspend fun interruptConversation(
        agentId: String,
        channelName: String,
    ) {
        api.interruptAgent(agentId, channelName)
    }

    suspend fun renewTokens(
        channel: String,
        rtcUid: Int,
        rtmUserId: String,
    ): RenewalTokens {
        return api.renewTokens(
            channel = channel,
            rtcUid = rtcUid,
            rtmUserId = rtmUserId,
        )
    }
}
