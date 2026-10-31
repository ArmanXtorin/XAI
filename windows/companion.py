"""
XAI Windows Companion (experimental)
Localhost-only, session-token protected, explicitly approved screenshot/read/edit.
No command execution, deletion, silent monitoring, or network listening.
Run with Python 3 on the Windows computer. Pillow is needed for screenshots.
"""
from __future__ import annotations

import difflib
import hashlib
import io
import json
import os
import queue
import secrets
import shutil
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tkinter import Tk, Button, Label, StringVar, filedialog, messagebox

HOST = "127.0.0.1"
PORT = 8765
MAX_FILE_BYTES = 250_000
MAX_REQUEST_BYTES = 500_000


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class Companion:
    def __init__(self) -> None:
        self.root = Tk()
        self.root.title("XAI Windows Companion - Local Only")
        self.root.geometry("650x330")
        self.token = secrets.token_urlsafe(32)
        self.allowed_root: Path | None = None
        self.requests: queue.Queue = queue.Queue()
        self.server = None
        Label(self.root, text="XAI Windows Companion", font=("Segoe UI", 16, "bold")).pack(pady=12)
        Label(self.root, text="Only software on THIS PC can connect (127.0.0.1).").pack()
        Label(self.root, text="Each screenshot, file read, or edit requires approval here.").pack()
        self.folder_text = StringVar(value="No folder authorized")
        Label(self.root, textvariable=self.folder_text, wraplength=600).pack(pady=12)
        Button(self.root, text="Allow a folder", command=self.select_folder).pack()
        Label(self.root, text="Session token (keep private):").pack(pady=(12, 0))
        Label(self.root, text=self.token, wraplength=590, font=("Consolas", 9)).pack()
        Label(self.root, text="A paired AI client may forward approved content to its cloud model.").pack(pady=8)
        Button(self.root, text="Exit and revoke access", command=self.stop).pack()
        self.root.protocol("WM_DELETE_WINDOW", self.stop)
        self.root.after(100, self.handle_approvals)

    def select_folder(self) -> None:
        selected = filedialog.askdirectory(title="Choose one folder XAI may access")
        if selected:
            self.allowed_root = Path(selected).resolve(strict=True)
            self.folder_text.set("Allowed folder: " + str(self.allowed_root))

    def resolve_file(self, relative: str) -> Path:
        if not isinstance(relative, str) or not relative or len(relative) > 500:
            raise ValueError("Invalid file path")
        path = Path(relative)
        if path.is_absolute() or ".." in path.parts or path.drive:
            raise ValueError("Only relative paths inside the selected folder are allowed")
        root = self.allowed_root
        if root is None:
            raise PermissionError("Select an allowed folder in the desktop window first")
        target = root.joinpath(path).resolve(strict=True)
        if not target.is_relative_to(root) or target.is_symlink() or not target.is_file():
            raise PermissionError("File is outside the authorized folder")
        return target

    def approve(self, title: str, details: str) -> bool:
        signal = threading.Event()
        response = {}
        self.requests.put((title, details, signal, response))
        return signal.wait(60) and bool(response.get("approved"))

    def handle_approvals(self) -> None:
        while True:
            try:
                title, details, signal, response = self.requests.get_nowait()
            except queue.Empty:
                break
            response["approved"] = messagebox.askyesno(title, details)
            signal.set()
        self.root.after(100, self.handle_approvals)

    def stop(self) -> None:
        if self.server:
            threading.Thread(target=self.server.shutdown, daemon=True).start()
        self.root.destroy()

    def serve(self) -> None:
        app = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, fmt, *args):
                # Avoid leaking tokens, file names, or content into terminal logs.
                pass

            def respond(self, code: int, obj: dict) -> None:
                data = json.dumps(obj).encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def authorized(self) -> bool:
                actual = self.headers.get("Authorization", "")
                return secrets.compare_digest(actual, "Bearer " + app.token)

            def do_GET(self) -> None:
                if not self.authorized():
                    self.respond(401, {"error": "Authentication required"})
                elif self.path == "/status":
                    self.respond(200, {"ready": True, "folder_selected": app.allowed_root is not None})
                else:
                    self.respond(404, {"error": "Not found"})

            def do_POST(self) -> None:
                if not self.authorized():
                    self.respond(401, {"error": "Authentication required"})
                    return
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    if length < 2 or length > MAX_REQUEST_BYTES:
                        self.respond(413, {"error": "Invalid request size"})
                        return
                    request = json.loads(self.rfile.read(length))
                    if not isinstance(request, dict):
                        raise ValueError("Expected JSON object")
                    if self.path == "/screen":
                        self.screenshot()
                    elif self.path == "/file/read":
                        self.read_file(request)
                    elif self.path == "/file/propose":
                        self.propose_edit(request)
                    else:
                        self.respond(404, {"error": "Not found"})
                except (OSError, UnicodeError, ValueError, PermissionError) as exc:
                    self.respond(400, {"error": str(exc)[:180]})

            def screenshot(self) -> None:
                if not app.approve("XAI screen request", "Allow XAI to capture the desktop once?\nThe screenshot may be forwarded to your configured AI service."):
                    self.respond(403, {"error": "Screen capture denied"})
                    return
                try:
                    from PIL import ImageGrab
                except ImportError:
                    self.respond(501, {"error": "Install Pillow to enable screenshots: py -m pip install pillow"})
                    return
                image = ImageGrab.grab()
                image.thumbnail((1280, 720))
                buffer = io.BytesIO()
                image.convert("RGB").save(buffer, "JPEG", quality=75)
                data = buffer.getvalue()
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def read_file(self, request: dict) -> None:
                path = app.resolve_file(request.get("path", ""))
                if path.stat().st_size > MAX_FILE_BYTES:
                    raise ValueError("File exceeds the text limit")
                if not app.approve("XAI file read", "Allow XAI to read this file?\n" + str(path)):
                    self.respond(403, {"error": "Read denied"})
                    return
                data = path.read_bytes()
                self.respond(200, {"path": str(path.relative_to(app.allowed_root)),
                                   "content": data.decode("utf-8"),
                                   "sha256": digest(data)})

            def propose_edit(self, request: dict) -> None:
                path = app.resolve_file(request.get("path", ""))
                content = request.get("content")
                expected_hash = request.get("sha256")
                if not isinstance(content, str) or not isinstance(expected_hash, str):
                    raise ValueError("Expected content and sha256")
                data = content.encode("utf-8")
                if len(data) > MAX_FILE_BYTES or path.stat().st_size > MAX_FILE_BYTES:
                    raise ValueError("File exceeds the text limit")
                previous = path.read_bytes()
                if digest(previous) != expected_hash:
                    self.respond(409, {"error": "File changed since last read"})
                    return
                diff = "\n".join(list(difflib.unified_diff(
                    previous.decode("utf-8").splitlines(), content.splitlines(),
                    fromfile="current", tofile="proposed", lineterm=""))[:30])
                if not app.approve("Approve XAI file edit", "Overwrite this file after creating a .bak backup?\n" +
                                   str(path) + "\n\nPreview:\n" + diff[:1800]):
                    self.respond(403, {"error": "Edit denied"})
                    return
                # Check again after the human review, then write atomically.
                if digest(path.read_bytes()) != expected_hash:
                    self.respond(409, {"error": "File changed during approval"})
                    return
                backup = path.with_name(path.name + ".bak")
                shutil.copy2(path, backup)
                temp = path.with_name(path.name + ".xai.tmp")
                try:
                    with open(temp, "wb") as output:
                        output.write(data)
                    os.replace(temp, path)
                finally:
                    if temp.exists():
                        temp.unlink()
                self.respond(200, {"saved": True, "backup": str(backup.name), "sha256": digest(data)})

        self.server = ThreadingHTTPServer((HOST, PORT), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.root.mainloop()


if __name__ == "__main__":
    Companion().serve()
