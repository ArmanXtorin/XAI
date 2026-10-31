# XAI: Memory, Vision, and Windows Companion (prototype)

This branch is experimental and deliberately **does not deploy to production**.
Cloudflare Access and the public-URL configuration must remain as they are until a secure user authentication design is added.

## Android: local memory

- Existing `remember KEY = VALUE`, `recall KEY`, and `clear memory KEY` commands still work.
- Navigation → **Saved memories** lets the user add, inspect, edit, or delete memories.
- Memories stay on the Android device in its private app preferences. Uninstalling the app deletes local memories. Android backups are disabled by the existing manifest.
- Settings → **Include local memories in cloud AI requests** is **OFF by default**. Turning it on sends saved text notes to the configured third-party/cloud model alongside messages.
- Automatic extraction of facts from conversations and cross-device synchronization are **not implemented**. Do not claim the app automatically learns a user's private facts.

## Android: vision

- Chat → the **🖼** button opens Android's document picker for images.
- An explicit dialog shows what will be uploaded and asks for a short question.
- The app resizes the image (maximum ~1024 px in each dimension), converts it to JPEG, and sends it by HTTPS only after confirmation.
- Set a user-approved endpoint with the suffix `/v1/chat/completions` in Settings. The app derives `/v1/vision/analyze` on the same host. Never supply a private token in a repository or screenshot.
- The app stores the question and the answer in the chat, but not the actual image bytes.
- XAI's Cloudflare Worker has a rate-limited POST `/v1/vision/analyze` route using `@cf/meta/llama-3.2-11b-vision-instruct`; it validates a JPEG data URI, limits request size, and does not stream.
- **Cloudflare requires accepting Meta's Llama 3.2 Vision license separately** before model inference works. See [official model documentation](https://developers.cloudflare.com/workers-ai/models/llama-3.2-11b-vision-instruct/).
- Existing Cloudflare Access currently blocks unauthenticated API calls, and workers.dev public URLs are disabled. Vision therefore **cannot work from the Android app yet**, even though the implementation is in this branch. Do not disable Access merely to make it work.

## Windows: permission-gated local companion

`windows/companion.py` is a **local prototype**, not yet paired to Android or XAI's cloud model.

1. On Windows, install Python 3 if needed; for screenshots optionally run `py -m pip install pillow`.
2. Run `py windows\companion.py` from the repository root.
3. In the companion's GUI, choose one folder XAI is allowed to access.
4. The app listens only on `127.0.0.1:8765` and generates a random session bearer token visible in the local window. Keep it private.
5. A future paired local XAI client can request:
   - `GET /status` (requires session token)
   - `POST /screen` with `{}` (capture one JPEG desktop screenshot, with Windows approval)
   - `POST /file/read` with `{"path":"notes.txt"}` (read an allowlisted UTF-8 file, with Windows approval; returns SHA-256)
   - `POST /file/propose` with `{"path":"notes.txt","content":"new content","sha256":"the previously returned hash"}` (show diff, obtain Windows approval, back up `notes.txt.bak` and atomically write)
6. Stopping the local GUI revokes the token and shuts down the service.

All endpoints except `/status` ask for permission **every time**. The allowed root must be deliberately selected. No remote port, background surveillance, shell execution, arbitrary file deletion, or unattended file writes.

**Important:** The Android app has no secure LAN pairing protocol yet. The desktop companion is an independent building block for the future Windows EXE, not a deployed screen-sharing feature. A production design needs client identity, short-lived pairing, TLS for any LAN connection, and explicit consent before uploading screen/file content to a cloud model.

## Development/testing checklist

- Confirm Android Gradle compile on the feature branch.
- Verify editable memories survive app restarts and delete correctly.
- Confirm upload cancel sends no image bytes.
- Accept model license, then test vision with a sample non-sensitive photo on an authenticated staging endpoint.
- Check Windows companion's Python syntax, then manually test screenshot and file edit approvals on a Windows PC.
- Only connect to XAI Cloud when the application-level authentication and per-user quotas are implemented.

No APK or EXE has been built or tested as part of these commits.
