from __future__ import annotations

import secrets
import time
from typing import Any

import httpx
from agora_agent import Agent, Area, AsyncAgora, DeepgramSTT, MiniMaxTTS, OpenAI
from agora_agent.agentkit import generate_convo_ai_token
from agora_agent.core.api_error import ApiError

from .config import Settings


DEFAULT_SYSTEM_PROMPT = """You are Agora AutoPilot — a friendly, concise voice assistant on an Android phone.

The user speaks English, often with an Indian English accent. Be warm, helpful, and extremely brief.

WHEN THE USER REQUESTS ANY PHONE ACTION OR TASK:
(Examples: opening an app like WhatsApp/YouTube/Instagram, setting an alarm, pausing/playing video, sending a message, scrolling, navigating, taking a screenshot, adjusting settings, etc.)
- Always respond IMMEDIATELY with a single short confirmation like: "Sure!", "OK!", "On it!", or "Got it!".
- NEVER say "Opening WhatsApp" or "Setting your alarm".
- NEVER output any XML, JSON, or code.
- NEVER say "Please say again" when the user asks for a phone action!

WHEN THE USER ASKS A GENERAL QUESTION (e.g. "what is the capital of France?", "tell me a joke"):
- Answer naturally, clearly, and concisely in 1-2 sentences.

ONLY IF THE USER'S SPEECH IS COMPLETE UNINTELLIGIBLE NOISE OR GIBBERISH:
- Say: "Please say again."
"""


class AgoraUpstreamError(RuntimeError):
    pass


class AgoraTimeoutError(TimeoutError):
    pass


AREA_BY_NAME = {
    "NORTH_AMERICA": Area.US,
    "US": Area.US,
    "EUROPE": Area.EU,
    "EU": Area.EU,
    "ASIA_PACIFIC": Area.AP,
    "AP": Area.AP,
    "CHINA": Area.CN,
    "CN": Area.CN,
}


class AgoraClient:
    def __init__(self, settings: Settings, http_client: httpx.AsyncClient | None = None) -> None:
        self.settings = settings
        self._http = http_client or httpx.AsyncClient(timeout=httpx.Timeout(60.0))
        self._owns_http = http_client is None
        area_name = settings.agora_area.strip().upper()
        if area_name not in AREA_BY_NAME:
            supported = ", ".join(sorted(AREA_BY_NAME))
            raise ValueError(f"Unsupported AGORA_AREA '{settings.agora_area}'. Use one of: {supported}.")
        self._client = AsyncAgora(
            area=AREA_BY_NAME[area_name],
            app_id=settings.agora_app_id,
            app_certificate=settings.agora_app_certificate,
            httpx_client=self._http,
        )
        self._sessions: dict[str, tuple[str, Any]] = {}

    def create_user_tokens(self, channel_name: str, rtc_uid: int) -> tuple[str, str, int]:
        token = generate_convo_ai_token(
            app_id=self.settings.agora_app_id,
            app_certificate=self.settings.agora_app_certificate,
            channel_name=channel_name,
            uid=rtc_uid,
            token_expire=self.settings.token_expiry_seconds,
        )
        expires_at = int(time.time()) + self.settings.token_expiry_seconds
        return token, token, expires_at

    def _build_agent(self, system_prompt: str | None = None) -> Agent:
        return (
            Agent(
                client=self._client,
                turn_detection={"language": "en-IN"},
                advanced_features={"enable_rtm": True},
                parameters={
                    "audio_scenario": "chorus",
                    "data_channel": "rtm",
                    "enable_error_message": True,
                    "enable_metrics": True,
                },
            )
            .with_stt(DeepgramSTT(
                model=self.settings.asr_model,
                language="en-IN"
            ))
            .with_llm(
                OpenAI(
                    model=self.settings.llm_model,
                    system_messages=[
                        {"role": "system", "content": system_prompt or DEFAULT_SYSTEM_PROMPT}
                    ],
                    greeting_message="Hi there!",
                    failure_message="Please wait a moment.",
                    max_history=15,
                    max_tokens=1024,
                    temperature=0.7,
                    top_p=0.95,
                )
            )
            .with_tts(
                MiniMaxTTS(
                    model=self.settings.tts_model,
                    voice_id=self.settings.tts_voice_id,
                )
            )
        )

    async def join_agent(
        self,
        channel_name: str,
        requester_rtc_uid: int,
        agent_profile: str | None = None,
        system_prompt: str | None = None,
    ) -> dict[str, Any]:
        session = self._build_agent(system_prompt).create_async_session(
            channel=channel_name,
            agent_uid=str(self.settings.agent_uid),
            remote_uids=[str(requester_rtc_uid)],
            name=f"android-server-agent-{int(time.time())}-{secrets.randbelow(9000) + 1000}",
            idle_timeout=30,
            preset=agent_profile,
            expires_in=self.settings.token_expiry_seconds,
            debug=True,
        )
        try:
            agent_id = await session.start()
        except httpx.TimeoutException as exc:
            raise AgoraTimeoutError("Agora request timed out.") from exc
        except (ApiError, httpx.HTTPError, RuntimeError, ValueError) as exc:
            logger.error(f"[Agora Error] Conversational AI start failed: {exc}")
            raise AgoraUpstreamError(f"Agora Conversational AI start failed: {exc}") from exc
        if not agent_id:
            raise AgoraUpstreamError("Agora response did not include agent_id.")
        self._sessions[agent_id] = (channel_name, session)
        return {
            "agent_id": agent_id,
            "create_ts": int(time.time()),
            "status": "started",
        }

    async def interrupt_agent(self, agent_id: str, channel_name: str) -> None:
        session = self._require_session(agent_id, channel_name)
        try:
            await session.interrupt()
        except httpx.TimeoutException as exc:
            raise AgoraTimeoutError("Agora interrupt request timed out.") from exc
        except (ApiError, httpx.HTTPError, RuntimeError) as exc:
            raise AgoraUpstreamError(f"Agora agent interrupt failed: {exc}") from exc

    async def leave_agent(self, agent_id: str, channel_name: str) -> None:
        session = self._require_session(agent_id, channel_name)
        try:
            await session.stop()
        except httpx.TimeoutException as exc:
            raise AgoraTimeoutError("Agora stop request timed out.") from exc
        except (ApiError, httpx.HTTPError, RuntimeError) as exc:
            raise AgoraUpstreamError(f"Agora agent stop failed: {exc}") from exc
        self._sessions.pop(agent_id, None)

    def _require_session(self, agent_id: str, channel_name: str) -> Any:
        active = self._sessions.get(agent_id)
        if active is None or active[0] != channel_name:
            raise AgoraUpstreamError("The Agora agent session is not active in this server process.")
        return active[1]

    async def close(self) -> None:
        if self._owns_http:
            await self._http.aclose()
