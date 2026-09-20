# StageMic

Wireless microphone for Android and mobile browsers. Phone → secure WebSocket → Laptop → Speaker.

---

## What's in this project

```
StageMic/
├── web_mic_receiver.py      ← Run this on your Windows laptop
├── app/                     ← Android app (open in Android Studio)
│   └── src/main/java/com/stagemic/app/
│       ├── MainActivity.kt      ← UI + connection logic
│       └── AudioStreamer.kt     ← Mic capture + WebSocket streaming
└── README.md
```

---

## Step 1 — Set up the laptop receiver

### Install Python dependencies

```bash
pip install sounddevice numpy
```

### Run the browser/WebSocket receiver

```bash
python web_mic_receiver.py
```

It will start a local HTTPS-ready receiver on port `8765`:

```
==================================================
  StageMic Receiver
==================================================

  Local server: http://127.0.0.1:8765
```

**Leave this running.** The Android app and browser both connect to its `/ws/audio` endpoint.

The receiver also advertises itself on the local network as `_stagemic._tcp.local.`.
Install the dependencies from `web_mic_requirements.txt` (including `zeroconf`) and
tap **FIND RECEIVER ON WI-FI** in the Android app to fill in the local WebSocket URL
automatically. Manual URLs and Cloudflare tunnel URLs remain supported.

### Create the phone link

In a second Command Prompt window, run:

```bat
cloudflared tunnel --url http://127.0.0.1:8765
```

Copy the generated `https://...trycloudflare.com` URL. The URL is temporary and changes when
the Quick Tunnel restarts, so paste the current URL into the APK each time.

---

## Step 2 — Build and install the Android app

### Requirements
- Android Studio (Hedgehog / 2023.1 or newer)
- Android phone with Android 8.0+ (API 26+)
- USB cable or Wi-Fi ADB

### Build steps
1. Open Android Studio
2. **File → Open** → select the `StageMic/` folder
3. Wait for Gradle sync to complete
4. Connect your phone via USB (enable Developer Options + USB Debugging)
5. Press **Run** (▶) to install

> The app will ask for **Microphone permission** on first use — tap Allow.

---

## Step 3 — Use it

1. Connect **both** devices to the same Wi-Fi network (or phone hotspot)
2. Start `receiver.py` on the laptop
3. Open **StageMic** on the phone
4. Tap **FIND RECEIVER ON WI-FI**, or paste the current HTTPS receiver link into the app
5. Tap **CONNECT**
6. **Hold the red HOLD TO TALK button** to speak
7. Release to stop

The laptop terminal shows a live level meter and packet stats:

```
  [████████████░░░░░░░░] RX:  1423  Loss: 0.2%  Buf: 3
```

---

## Audio settings

| Setting       | Value  | Why                                      |
|---------------|--------|------------------------------------------|
| Sample rate   | 16 kHz | Good for voice, low bandwidth            |
| Bit depth     | 16-bit | Standard PCM                             |
| Channels      | Mono   | Voice only needs mono                    |
| Frame size    | 20ms   | ~640 bytes per WebSocket message        |
| Pre-buffer    | 3 frames = ~60ms | Balances latency vs stability |

To change the buffer (trade latency for stability), edit `receiver.py`:

```python
BUFFER_FRAMES = 3   # reduce to 1–2 for lower latency, increase if glitchy
```

---

## Network setup options

### Option A — Cloudflare Quick Tunnel
Run `cloudflared tunnel --url http://127.0.0.1:8765` and use the generated HTTPS URL in
both the browser and APK.

### Option B — Laptop hotspot (no router needed)
1. Windows Settings → Network → Mobile Hotspot → Turn on
2. Connect your phone to the laptop's hotspot
3. The laptop IP will typically be `192.168.137.1`

### Option C — Phone hotspot
1. Enable hotspot on your phone
2. Connect laptop to the phone's hotspot
3. Run `receiver.py` — it shows the IP to enter in the app

---

## Troubleshooting

| Problem | Fix |
|---|---|
| No audio on laptop | Check that both receiver and tunnel windows are still running |
| App can't connect | Paste the current HTTPS link and grant microphone permission |
| Choppy audio | Increase `BUFFER_FRAMES` in receiver.py |
| High latency | Decrease `BUFFER_FRAMES` (minimum 1) |
| Permission denied | Grant microphone permission in Android Settings → Apps → StageMic |
| Latency shows "—" | Normal if Windows Firewall blocks the ping port — audio still works |

---

## What's next (Version 2 ideas)

- [ ] Auto-discovery (no manual IP entry)
- [ ] Opus compression (lower bandwidth, same quality)
- [ ] Multiple phones / mic switching
- [ ] Desktop GUI for the receiver (instead of terminal)
- [ ] Noise gate (auto-mute when silent)

---

## Technical notes

**Why WebSocket?** WebSocket uses the same secure transport as the browser version, so the APK
and browser can use one public HTTPS receiver instead of requiring a local IP and UDP firewall rule.
The receiver supports up to eight simultaneous microphones and mixes them into the laptop output.

**Why 16kHz?** Voice is intelligible at 8kHz and excellent at 16kHz. At 16kHz mono PCM the bitrate is only ~256kbps — trivial on any Wi-Fi network.

**Packet loss tolerance:** The receiver skips lost packets automatically. As long as packet loss stays under ~5%, audio is clear.
