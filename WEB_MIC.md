# StageMic browser microphone

This is a second way to use StageMic. It does not need the Android app: the PC creates a temporary HTTPS link, and a phone opens that link in its browser to become a live microphone.

## First-time setup

1. Install Python 3 if `py` is not already available.
2. Install Cloudflare Tunnel (`cloudflared`) for Windows and make sure its folder is on your `PATH`: <https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/>.
3. Double-click `run_web_mic.bat`.

The script installs the required Python packages, starts the speaker receiver, and prints a random link similar to:

```text
https://green-example.trycloudflare.com
```

Copy that **HTTPS** link to your phone and open it in Chrome, Safari, or another modern browser. Tap **START MICROPHONE**, then allow microphone permission. The phone's audio will play through the PC's default speaker. Up to eight phones
or APKs can stream at the same time; the receiver mixes their audio together.

Keep the two PC windows open. Press `Ctrl+C` in the tunnel window when finished and close the receiver window.

## Notes

- The link is temporary and changes each time `run_web_mic.bat` is started.
- The phone and PC do not need to be on the same Wi-Fi, but both need internet access while using the temporary link.
- Browser microphone access requires HTTPS. The temporary Cloudflare link provides that; a plain `http://PC-IP-address` link does not.
- The link is a live audio endpoint. Share it only with the phone you intend to use, and close the tunnel after the session.
- All connected microphones are mixed to one output. Each active source is averaged
  to reduce clipping when several people speak at once.
