"""StageMic browser receiver.

Serves phone microphones and mixes their PCM audio on this PC.  It is
intended to be exposed through an HTTPS tunnel (see WEB_MIC.md), because phone
browsers only grant microphone access to secure pages.
"""

import asyncio
import collections
import pathlib
import socket
import threading
import uuid

from aiohttp import web, WSMsgType
import numpy as np
import sounddevice as sd
from zeroconf import ServiceInfo, Zeroconf


HOST = "0.0.0.0"
PORT = 8765
SERVICE_TYPE = "_stagemic._tcp.local."
SAMPLE_RATE = 16_000
CHANNELS = 1
BLOCK_SIZE = 320                 # 20 ms
MAX_BUFFERED_SAMPLES = BLOCK_SIZE * 10  # cap latency at about 200 ms
WEB_DIR = pathlib.Path(__file__).with_name("web_mic")

MAX_CLIENTS = 8
client_chunks: dict[str, collections.deque[np.ndarray]] = {}
client_queued_samples: dict[str, int] = {}
audio_lock = threading.Lock()
next_client_id = 0


def add_audio(client_id: str, data: bytes) -> None:
    """Append one client's PCM audio, dropping old audio if it falls behind."""
    if len(data) < 2:
        return
    # Copy because WebSocket message storage can be released after this call.
    samples = np.frombuffer(data[: len(data) - len(data) % 2], dtype="<i2").copy()
    with audio_lock:
        chunks = client_chunks.get(client_id)
        if chunks is None:
            return
        queued_samples = client_queued_samples[client_id]
        while chunks and queued_samples + len(samples) > MAX_BUFFERED_SAMPLES:
            queued_samples -= len(chunks.popleft())
        if len(samples) <= MAX_BUFFERED_SAMPLES:
            chunks.append(samples)
            client_queued_samples[client_id] = queued_samples + len(samples)


def audio_callback(outdata, frames, _time_info, _status) -> None:
    """Mix all connected clients and always provide exactly ``frames`` samples."""
    outdata.fill(0)
    mixed = np.zeros(frames, dtype=np.int32)
    active_clients = 0
    with audio_lock:
        for client_id, chunks in client_chunks.items():
            if not chunks:
                continue
            active_clients += 1
            written = 0
            while written < frames and chunks:
                chunk = chunks[0]
                count = min(frames - written, len(chunk))
                mixed[written : written + count] += chunk[:count].astype(np.int32)
                written += count
                client_queued_samples[client_id] -= count
                if count == len(chunk):
                    chunks.popleft()
                else:
                    chunks[0] = chunk[count:]
    if active_clients:
        mixed //= active_clients
        outdata[:, 0] = np.clip(mixed, -32768, 32767).astype(np.int16)


async def home(_request: web.Request) -> web.FileResponse:
    return web.FileResponse(WEB_DIR / "index.html")


async def health(_request: web.Request) -> web.Response:
    with audio_lock:
        connected_clients = len(client_chunks)
    return web.json_response({"status": "ok", "connectedClients": connected_clients})


async def audio_socket(request: web.Request) -> web.WebSocketResponse:
    """Receive raw PCM-16 audio from one client in the shared mixer."""
    global next_client_id
    socket = web.WebSocketResponse(max_msg_size=256 * 1024, heartbeat=20)
    await socket.prepare(request)

    with audio_lock:
        if len(client_chunks) >= MAX_CLIENTS:
            at_capacity = True
        else:
            at_capacity = False
            next_client_id += 1
            client_id = f"{next_client_id}-{uuid.uuid4().hex[:6]}"
            client_chunks[client_id] = collections.deque()
            client_queued_samples[client_id] = 0

    if at_capacity:
        await socket.send_json({"type": "error", "message": "Receiver is full. Try again later."})
        await socket.close(code=4002, message=b"Receiver full")
        return socket

    peer = request.remote or "phone"
    with audio_lock:
        connected_count = len(client_chunks)
    print(f"\n  Microphone connected: {peer} ({connected_count} active)", flush=True)
    await socket.send_json({
        "type": "ready",
        "sampleRate": SAMPLE_RATE,
        "connectedClients": connected_count,
    })

    try:
        async for message in socket:
            if message.type is WSMsgType.BINARY:
                add_audio(client_id, message.data)
            elif message.type is WSMsgType.ERROR:
                print(f"  Browser connection error: {socket.exception()}", flush=True)
    finally:
        with audio_lock:
            client_chunks.pop(client_id, None)
            client_queued_samples.pop(client_id, None)
            connected_count = len(client_chunks)
        print(f"  Microphone disconnected ({connected_count} active).", flush=True)
    return socket


def make_app() -> web.Application:
    app = web.Application()
    app.router.add_get("/", home)
    app.router.add_get("/health", health)
    app.router.add_get("/ws/audio", audio_socket)
    return app


def local_ip() -> str:
    """Return the LAN address used to advertise the receiver."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))
        return probe.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        probe.close()


def advertise_receiver() -> tuple[Zeroconf, ServiceInfo]:
    address = local_ip()
    service = ServiceInfo(
        SERVICE_TYPE,
        "StageMic Receiver._stagemic._tcp.local.",
        addresses=[socket.inet_aton(address)],
        port=PORT,
        properties={b"path": b"/ws/audio", b"scheme": b"ws"},
    )
    zeroconf = Zeroconf()
    zeroconf.register_service(service)
    print(f"  LAN discovery: {address}:{PORT} (_stagemic._tcp.local.)")
    return zeroconf, service


def main() -> None:
    print("=" * 54)
    print("  StageMic Browser Receiver")
    print("=" * 54)
    print(f"\n  Local server: http://127.0.0.1:{PORT}")
    print("  Start run_web_mic.bat for an HTTPS phone link, or use LAN discovery.\n")

    zeroconf, service = advertise_receiver()
    try:
        with sd.OutputStream(
            samplerate=SAMPLE_RATE,
            channels=CHANNELS,
            dtype="int16",
            blocksize=BLOCK_SIZE,
            callback=audio_callback,
            latency="low",
        ) as stream:
            print(f"  Audio output: {sd.query_devices(stream.device, 'output')['name']}")
            print("  Waiting for a phone...\n")
            web.run_app(make_app(), host=HOST, port=PORT, print=None)
    finally:
        zeroconf.unregister_service(service)
        zeroconf.close()


if __name__ == "__main__":
    main()
