"""
StageMic Receiver — Windows Laptop
===================================
Receives raw 16-bit PCM audio from the Android StageMic app via UDP
and plays it through your speakers in near-real-time.

Usage:
    python receiver.py

Requirements:
    pip install sounddevice

Packet format from the Android app:
    [4 bytes] sequence number  (little-endian int32)
    [2 bytes] sample rate      (little-endian int16)  e.g. 16000
    [2 bytes] channels         (little-endian int16)  always 1
    [N bytes] raw PCM samples  (little-endian int16 per sample)
"""

import socket
import struct
import threading
import time
import collections
import sys

try:
    import sounddevice as sd
    import numpy as np
except ImportError:
    print("Missing dependency. Run:  pip install sounddevice numpy")
    sys.exit(1)

# ─── Config ──────────────────────────────────────────────────────────────────

UDP_PORT      = 9876          # Must match Android app
PING_PORT     = UDP_PORT + 1  # Latency ping/pong port
SAMPLE_RATE   = 16000         # Must match Android app
CHANNELS      = 1
HEADER_SIZE   = 8             # seq(4) + sampleRate(2) + channels(2)
FRAME_SAMPLES = 320           # Samples per packet (20ms at 16kHz)
FRAME_BYTES   = FRAME_SAMPLES * 2

# Buffer: how many frames to pre-buffer before playing.
# Higher = more stable, but more latency.
# Lower = lower latency, but may glitch on weak Wi-Fi.
BUFFER_FRAMES = 3             # ~60ms pre-buffer — good starting point

# ─── Stats tracking ──────────────────────────────────────────────────────────

class Stats:
    def __init__(self):
        self.packets_received = 0
        self.packets_dropped  = 0   # estimated from sequence gaps
        self.last_seq         = -1
        self.start_time       = None
        self.level            = 0   # 0–100 audio level for display
        self.lock             = threading.Lock()

    def record_packet(self, seq: int, pcm: np.ndarray):
        with self.lock:
            if self.start_time is None:
                self.start_time = time.time()
            if self.last_seq >= 0 and seq > self.last_seq + 1:
                self.packets_dropped += seq - self.last_seq - 1
            self.last_seq = seq
            self.packets_received += 1
            # Simple RMS level
            rms = np.sqrt(np.mean(pcm.astype(np.float32) ** 2))
            self.level = int(min(100, rms / 32767 * 100 * 3))

    def loss_pct(self) -> float:
        with self.lock:
            total = self.packets_received + self.packets_dropped
            if total == 0:
                return 0.0
            return self.packets_dropped / total * 100

stats = Stats()

# ─── Audio playback ──────────────────────────────────────────────────────────

# Thread-safe queue of numpy arrays (each is one frame of int16 samples)
audio_queue: collections.deque = collections.deque(maxlen=32)
queue_lock = threading.Lock()
playback_started = threading.Event()

def audio_callback(outdata, frames, time_info, status):
    """Called by sounddevice on a real-time audio thread. Must be fast."""
    with queue_lock:
        if len(audio_queue) > 0:
            chunk = audio_queue.popleft()
            if len(chunk) == frames:
                outdata[:, 0] = chunk
            else:
                # Partial frame — fill rest with silence
                outdata[:len(chunk), 0] = chunk
                outdata[len(chunk):, 0] = 0
        else:
            # Buffer underrun — output silence
            outdata[:] = 0

def start_audio_output():
    """Open the audio output stream. Blocks until stopped."""
    stream = sd.OutputStream(
        samplerate=SAMPLE_RATE,
        channels=CHANNELS,
        dtype='int16',
        blocksize=FRAME_SAMPLES,
        callback=audio_callback,
        latency='low'
    )
    with stream:
        print(f"  Audio output: {sd.query_devices(stream.device, 'output')['name']}")
        print(f"  Blocksize: {FRAME_SAMPLES} samples ({FRAME_SAMPLES/SAMPLE_RATE*1000:.0f}ms per block)")
        playback_started.set()
        # Keep stream alive until keyboard interrupt
        while True:
            time.sleep(0.1)

# ─── UDP audio receiver ───────────────────────────────────────────────────────

def receive_audio():
    """Listens for UDP packets from the phone and pushes audio to the queue."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 65536)
    sock.bind(('0.0.0.0', UDP_PORT))
    sock.settimeout(2.0)

    print(f"\n  Listening on UDP port {UDP_PORT}…")
    print("  Waiting for phone to connect…\n")

    # Wait for first packet before starting playback gate
    buffering = True
    buffer_count = 0

    while True:
        try:
            data, addr = sock.recvfrom(2048)
        except socket.timeout:
            continue

        if len(data) < HEADER_SIZE:
            continue  # Malformed packet

        # Parse header
        seq, sample_rate, ch = struct.unpack_from('<iHH', data, 0)
        pcm_bytes = data[HEADER_SIZE:]

        if len(pcm_bytes) == 0:
            continue

        # Convert raw bytes → int16 numpy array
        pcm = np.frombuffer(pcm_bytes, dtype='<i2')  # little-endian int16

        stats.record_packet(seq, pcm)

        if buffering:
            if stats.packets_received == 1:
                print(f"  Connected: {addr[0]}:{addr[1]}")
                print(f"  Stream: {sample_rate} Hz, {ch}ch, {len(pcm_bytes)} bytes/packet")

            # Collect BUFFER_FRAMES before starting playback to reduce underruns
            with queue_lock:
                audio_queue.append(pcm)
            buffer_count += 1

            if buffer_count >= BUFFER_FRAMES:
                buffering = False
                print(f"  Buffer ready ({BUFFER_FRAMES} frames). Playing audio…\n")
        else:
            with queue_lock:
                audio_queue.append(pcm)

# ─── Ping/pong handler ────────────────────────────────────────────────────────

def handle_pings():
    """Echoes ping packets back to the phone for RTT measurement."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(('0.0.0.0', PING_PORT))
    sock.settimeout(1.0)
    ping_count = 0

    while True:
        try:
            data, addr = sock.recvfrom(64)
            if len(data) >= 4 and data[:4] == b'PING':
                # Echo back immediately
                pong = b'PONG' + data[4:]
                sock.sendto(pong, addr)
                ping_count += 1
                if ping_count == 1:
                    print(f"\n  >>> Phone connected! (Ping from {addr[0]})\n", flush=True)
        except (socket.timeout, OSError):
            # On Windows, socket timeout can raise OSError (WinError 10060)
            continue

# ─── Stats display ────────────────────────────────────────────────────────────

def display_stats():
    """Prints a live stats line to the terminal every second."""
    playback_started.wait()  # Don't print until audio is ready
    while True:
        time.sleep(1)
        with stats.lock:
            rx   = stats.packets_received
            loss = stats.loss_pct()
            lvl  = stats.level
            buf  = len(audio_queue)

        filled = lvl // 5
        bar = '#' * filled + '-' * (20 - filled)
        print(f"\r  [{bar}] RX:{rx:6d}  Loss:{loss:4.1f}%  Buf:{buf:2d}  ", end='', flush=True)

# ─── Entry point ─────────────────────────────────────────────────────────────

def get_all_ips():
    """Return all IPv4 addresses on this machine (one per network interface)."""
    ips = []
    try:
        hostname = socket.gethostname()
        for addr_info in socket.getaddrinfo(hostname, None):
            ip = addr_info[4][0]
            if ip.startswith('192.') or ip.startswith('10.') or ip.startswith('172.'):
                if ip not in ips:
                    ips.append(ip)
    except Exception:
        pass
    # Fallback: get the default outbound IP
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        fallback = s.getsockname()[0]
        s.close()
        if fallback not in ips:
            ips.insert(0, fallback)
    except Exception:
        pass
    return ips if ips else ["unknown"]

def main():
    print("=" * 50)
    print("  StageMic Receiver")
    print("=" * 50)

    ips = get_all_ips()
    print(f"\n  Laptop IP addresses (enter one of these in the app):")
    for ip in ips:
        print(f"    --> {ip}")
    print(f"\n  TIP: If using Phone Hotspot, use the IP that starts with 192.168.x.x")
    print(f"  TIP: Turn OFF mobile data on your phone before connecting!\n")

    # Start background threads
    threading.Thread(target=receive_audio, daemon=True).start()
    threading.Thread(target=handle_pings,  daemon=True).start()
    threading.Thread(target=display_stats, daemon=True).start()

    # Audio output runs on the main thread (blocking)
    try:
        start_audio_output()
    except KeyboardInterrupt:
        print("\n\n  Stopped.")
        sys.exit(0)

if __name__ == '__main__':
    main()
