# Xtorin AI Desktop for Windows

This is an Electron wrapper around https://xtorin-ai-ec7c0363.base44.app. Changes published to the website appear in the app after refresh.

## Downloads

See the repository **Releases** page for Windows installers when a successful release build has completed. An installer is not included in this source tree.

## Local build

Install Node.js 20+, then run:

```bash
cd windows
npm install
npm run dist:win
```

The unsigned Windows x64 NSIS installer and portable EXE are written to `windows/dist`. Windows SmartScreen may warn about unsigned apps. Do not bypass warnings unless you trust the source.

## Caveats

Google OAuth, file selection, audio recording, and notification permissions require testing. The desktop wrapper cannot add features absent from the website. External authentication links open in your default browser.
