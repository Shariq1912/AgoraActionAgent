# Android AutoPilot Voice Action Agent

An autonomous voice assistant for Android built with **Agora Conversational AI** and **Gemini Vision**. It listens to natural voice instructions in real time and executes hands-free UI actions (tap, swipe, type, dismiss recents, scroll wheels, set alarms) directly on the device using Android's Accessibility Service.

## Key Features

- **Real-time Conversational Voice**: Powered by Agora Conversational AI, Deepgram ASR (`en-IN` Indian English), and MiniMax TTS.
- **Natural Intent Recognition**: Speaks friendly confirmations aloud before initiating tasks without reading out code or raw JSON.
- **Autonomous Vision & UI Execution**: Uses Gemini Vision to parse Android UI accessibility layouts and dynamically calculate touch coordinates and gestures.
- **App Control Capabilities**:
  - Open & close apps
  - Dismiss specific app cards from Recents (smart gesture pathing)
  - Control video playback (YouTube tap-to-reveal & pause)
  - Set alarm scroll wheels (`NumberPicker`)
  - Navigate Google Maps & send messages

## System Architecture

```
User Voice Input
       │
       ▼
Agora Conversational AI (Cloud Agent)
 ├── Deepgram ASR (en-IN)
 ├── GPT-4o-mini LLM
 └── MiniMax TTS
       │
  RTM Action Tag: <action tool="agora_auto_pilot" command="..."/>
       │
       ▼
Android App (ActionAccessibilityService)
       │
  Screen Layout XML
       │
       ▼
Gemini Vision Model (Decide Next UI Action)
       │
       ▼
Android Accessibility API (Perform Tap / Swipe / Type)
```

## Prerequisites

- Android Studio Jellyfish or newer
- Android Device running Android 10+ (API 29+)
- Agora App ID & App Certificate
- Python 3.10+ (for the backend proxy server)
- Google Gemini API Key

## Setup & Configuration

### 1. Backend Server Setup

```bash
cd server
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

Create a `.env` file inside the `server/` directory:
```env
AGORA_APP_ID=your_agora_app_id
AGORA_APP_CERTIFICATE=your_agora_app_certificate
AGORA_AGENT_UID=123456
AGORA_AREA=NORTH_AMERICA
ASR_MODEL=nova-3
LLM_MODEL=gpt-4o-mini
TTS_MODEL=speech_2_6_turbo
TTS_VOICE_ID=English_captivating_female1
HOST=127.0.0.1
PORT=8000
```

Start the backend:
```bash
./run.sh
```

Expose the local server to the internet using localtunnel or ngrok:
```bash
npx localtunnel --port 8000
```

### 2. Android App Setup

1. Open the project in Android Studio.
2. In `local.properties`, set your localtunnel server URL:
   ```properties
   QUICKSTART_SERVER_URL=https://your-tunnel-url.loca.lt
   ```
3. Build and install the app on your Android device.
4. Grant Accessibility permissions:
   - Go to **Settings > Accessibility > Installed Apps > Agora AutoPilot**.
   - Turn **ON** accessibility access.

## Usage

1. Launch the app and tap **Connect**.
2. Speak naturally to the agent:
   - *"Open WhatsApp and text Mom"*
   - *"Pause the video on YouTube"*
   - *"Close Camera app from recent apps"*
   - *"Set an alarm for 5 AM tomorrow"*
