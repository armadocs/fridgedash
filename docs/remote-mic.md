# Remote mic protocol

FridgeDash on the fridge listens on **TCP port 47800** and announces itself on the
local network as mDNS/DNS-SD service type `_fridgedash-mic._tcp`.
A microphone device (the FridgeDash "Fridge Mic" app on a phone, or an ESP32/ESP8266
board) connects and streams audio while a ChatGPT call is open.

## Device → fridge
1. One text line: `FDMIC1 rate=<Hz> name=<device name>\n` (rate 8000–48000; 16000 or 24000 recommended).
2. Then binary frames: `[type: 1 byte][length: 2 bytes, big-endian][payload]`
   - `A` audio: PCM 16-bit little-endian mono at the declared rate (about 100 ms per frame)
   - `T` start a ChatGPT call on the fridge (e.g. a button on the device)
   - `E` end the call
   - `P` keep-alive (send one every ≤5 s; the fridge drops silent connections after 15 s)

## Fridge → device (text lines)
- `HELLO FridgeDash` after the hello line is accepted
- `START` / `STOP`: begin / stop sending audio (only sent while a call is open)
- `STATE listening|hearing|thinking|speaking|ended`: what ChatGPT is doing
- `PING` every 5 s (answer with a `P` frame)

Only one device is used at a time; a new connection replaces the old one.
