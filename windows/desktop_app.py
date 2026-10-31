"""XAI Desktop: prototype Windows chat, memories, screenshot analysis, file editing.
Cloud features need a trusted HTTPS endpoint with application authentication.
No background surveillance, no remote control port, and no unattended file edits.
"""
from __future__ import annotations

import base64
import io
import json
import os
import shutil
import threading
import tkinter as tk
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from tkinter import filedialog, messagebox, scrolledtext, ttk
from urllib.parse import urlsplit

from PIL import Image, ImageGrab, ImageTk

DATA_DIR = Path(os.environ.get("APPDATA", str(Path.home()))) / "XAI"
STATE_FILE = DATA_DIR / "desktop_state.json"
MAX_FILE = 200_000


def safe_https(url: str) -> str:
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Use a trusted HTTPS endpoint without credentials in its URL")
    if parsed.fragment:
        raise ValueError("Do not use URL fragments in endpoints")
    return url


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


class XAI(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("XAI Desktop | Xtorin AI")
        self.geometry("980x690")
        self.minsize(760, 540)
        self.configure(bg="#0d1424")
        self.state_data = self.read_state()
        self.current_file = None
        self.file_original = None
        self.screenshot = None
        self.preview_photo = None
        self.busy = False
        self.build_ui()
        self.protocol("WM_DELETE_WINDOW", self.close)

    @staticmethod
    def read_state():
        try:
            value = json.loads(STATE_FILE.read_text(encoding="utf-8"))
            if isinstance(value, dict):
                return value
        except (OSError, json.JSONDecodeError):
            pass
        return {"memory": {}, "chats": [], "endpoint": "", "model": "",
                "share_memory": False, "humor": 60, "sarcasm": 55,
                "custom_instructions": ""}

    def persist(self):
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        temporary = STATE_FILE.with_suffix(".tmp")
        temporary.write_text(json.dumps(self.state_data, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(temporary, STATE_FILE)

    def button(self, parent, text, handler):
        return ttk.Button(parent, text=text, command=handler)

    def build_ui(self):
        style = ttk.Style(self)
        style.theme_use("clam")
        style.configure("TFrame", background="#0d1424")
        style.configure("TLabel", background="#0d1424", foreground="#e4edff", font=("Segoe UI", 10))
        style.configure("TButton", background="#233855", foreground="#e4edff", padding=8)
        style.map("TButton", background=[("active", "#385b88")])
        style.configure("TNotebook", background="#0d1424", borderwidth=0)
        style.configure("TNotebook.Tab", background="#192840", foreground="#e4edff", padding=[18, 10])
        style.map("TNotebook.Tab", background=[("selected", "#385b88")])

        ttk.Label(self, text="◈ XAI Desktop", font=("Segoe UI", 22, "bold")).pack(pady=(15, 3))
        ttk.Label(self, text="LOCAL MEMORY • OPTIONAL CLOUD AI • APPROVAL-BASED DEVICE ACCESS").pack()
        self.tabs = ttk.Notebook(self)
        self.tabs.pack(fill="both", expand=True, padx=16, pady=14)
        self.chat_tab = ttk.Frame(self.tabs)
        self.memory_tab = ttk.Frame(self.tabs)
        self.screen_tab = ttk.Frame(self.tabs)
        self.files_tab = ttk.Frame(self.tabs)
        self.settings_tab = ttk.Frame(self.tabs)
        for tab, name in [(self.chat_tab, "Chat"), (self.memory_tab, "Memory"),
                          (self.screen_tab, "Screen"), (self.files_tab, "Files"),
                          (self.settings_tab, "Settings")]:
            self.tabs.add(tab, text=name)
        self.build_chat()
        self.build_memory()
        self.build_screen()
        self.build_files()
        self.build_settings()
        ttk.Label(self, text="Cloud inference is unavailable until you configure a working authenticated endpoint. "
                         "Screenshots and file contents are never uploaded without asking.").pack(pady=(0, 8))

    def build_chat(self):
        self.chat_area = scrolledtext.ScrolledText(
            self.chat_tab, bg="#121c31", fg="#e4edff", insertbackground="white",
            font=("Consolas", 11), wrap="word", borderwidth=0)
        self.chat_area.pack(fill="both", expand=True, pady=8)
        self.chat_area.tag_configure("you", foreground="#91dbf9")
        self.chat_area.tag_configure("xai", foreground="#e6b4ff")
        self.chat_area.configure(state="disabled")
        for msg in self.state_data.get("chats", [])[-100:]:
            self.append_chat(msg.get("role", "assistant"), msg.get("text", ""), save=False)
        line = ttk.Frame(self.chat_tab)
        line.pack(fill="x")
        self.input_text = ttk.Entry(line, font=("Segoe UI", 11))
        self.input_text.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self.input_text.bind("<Return>", lambda _: self.send())
        self.button(line, "Send", self.send).pack(side="left")
        self.button(line, "New chat", self.new_chat).pack(side="left", padx=8)

    def append_chat(self, role, text, save=True):
        self.chat_area.configure(state="normal")
        tag = "you" if role == "user" else "xai"
        self.chat_area.insert("end", ("YOU: " if tag == "you" else "XAI: "), tag)
        self.chat_area.insert("end", text + "\n\n")
        self.chat_area.see("end")
        self.chat_area.configure(state="disabled")
        if save:
            self.state_data.setdefault("chats", []).append({"role": role, "text": text})
            self.state_data["chats"] = self.state_data["chats"][-150:]
            self.persist()

    def new_chat(self):
        if messagebox.askyesno("New conversation", "Clear the conversation on this device? Saved memories will remain."):
            self.state_data["chats"] = []
            self.persist()
            self.chat_area.configure(state="normal")
            self.chat_area.delete("1.0", "end")
            self.chat_area.configure(state="disabled")

    def send(self):
        message = self.input_text.get().strip()
        if not message:
            return
        self.input_text.delete(0, "end")
        self.append_chat("user", message)
        lowered = message.lower()
        if lowered.startswith("remember ") and "=" in message:
            key, val = message[9:].split("=", 1)
            if key.strip() and val.strip():
                self.state_data.setdefault("memory", {})[key.strip()[:80]] = val.strip()[:2000]
                self.persist()
                self.refresh_memories()
                self.append_chat("assistant", "Saved that memory locally.")
            else:
                self.append_chat("assistant", "Use: remember KEY = VALUE")
            return
        if lowered.startswith("recall "):
            query = message[7:].strip()
            result = self.state_data.get("memory", {}).get(query)
            self.append_chat("assistant", result or "I don't have a memory under that name.")
            return
        if lowered == "memories":
            memory = self.state_data.get("memory", {})
            self.append_chat("assistant", "\n".join(f"{k}: {v}" for k, v in memory.items()) or "No saved memories.")
            return
        if not self.endpoint.get().strip():
            self.append_chat("assistant", "Local memory is available. Cloud chat requires a configured HTTPS endpoint "
                                            "and working application authentication.")
            return
        if self.busy:
            self.append_chat("assistant", "Another AI request is still processing.")
            return
        messages = []
        if self.share_memory.get():
            memory = json.dumps(self.state_data.get("memory", {}), ensure_ascii=False)[:3000]
            messages.append({"role": "system", "content": self.personality() + "\nUser-approved saved notes:\n" + memory})
        else:
            messages.append({"role": "system", "content": self.personality()})
        for item in self.state_data.get("chats", [])[-16:]:
            if item.get("role") in ("user", "assistant") and item.get("text"):
                messages.append({"role": item["role"], "content": item["text"][:5000]})
        self.busy = True
        self.run_async(lambda: self.chat_request(messages),
                       lambda answer: self.append_chat("assistant", answer), "Cloud chat")

    def personality(self):
        return ("You are XAI, Xtorin AI. Be natural, accurate, helpful and candid. "
                "Recognize humor; don't blindly agree. Admit uncertainty and mistakes. "
                f"Humor preference {int(self.humor.get())}/100; sarcasm {int(self.sarcasm.get())}/100. "
                + self.custom_instructions.get("1.0", "end").strip()[:1500])

    def run_async(self, work, success, label):
        def runner():
            try:
                result = work()
                self.results.put((success, result, None, label))
            except Exception as exc:
                self.results.put((success, None, str(exc), label))
        if not hasattr(self, "results"):
            import queue
            self.results = queue.Queue()
            self.after(80, self.poll_results)
        threading.Thread(target=runner, daemon=True).start()

    def poll_results(self):
        while not self.results.empty():
            success, result, error, label = self.results.get_nowait()
            self.busy = False
            if error:
                self.append_chat("assistant", label + " failed: " + error[:250])
            else:
                success(result)
        self.after(80, self.poll_results)

    def request_json(self, url, payload):
        url = safe_https(url)
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(url, data=data, method="POST",
                                     headers={"Content-Type": "application/json",
                                              "User-Agent": "XAI-Desktop/0.1"})
        if self.api_key.get().strip():
            req.add_header("Authorization", "Bearer " + self.api_key.get().strip())
        opener = urllib.request.build_opener(NoRedirect())
        try:
            with opener.open(req, timeout=45) as response:
                return json.load(response)
        except urllib.error.HTTPError as exc:
            raise RuntimeError(f"HTTP {exc.code}. Check endpoint, Access authentication, and quota.") from exc

    def chat_request(self, messages):
        payload = {"messages": messages, "temperature": 0.7}
        if self.model.get().strip():
            payload["model"] = self.model.get().strip()
        result = self.request_json(self.endpoint.get().strip(), payload)
        return result.get("choices", [{}])[0].get("message", {}).get("content", "") or "Empty AI response."

    def build_memory(self):
        ttk.Label(self.memory_tab, text="Memories stay on this PC. Editing and deletion are always available.").pack(pady=10)
        self.memories_list = tk.Listbox(self.memory_tab, bg="#121c31", fg="#e4edff",
                                        selectbackground="#385b88", font=("Segoe UI", 11))
        self.memories_list.pack(fill="both", expand=True)
        self.memories_list.bind("<<ListboxSelect>>", self.choose_memory)
        form = ttk.Frame(self.memory_tab)
        form.pack(fill="x", pady=8)
        ttk.Label(form, text="Name").pack(side="left")
        self.memory_key = ttk.Entry(form, width=25)
        self.memory_key.pack(side="left", padx=8)
        ttk.Label(form, text="Note").pack(side="left")
        self.memory_value = ttk.Entry(form)
        self.memory_value.pack(side="left", fill="x", expand=True, padx=8)
        self.button(form, "Save", self.save_memory).pack(side="left", padx=4)
        self.button(form, "Delete", self.delete_memory).pack(side="left")
        self.refresh_memories()

    def refresh_memories(self):
        self.memories_list.delete(0, "end")
        self.memory_keys = sorted(self.state_data.get("memory", {}))
        for name in self.memory_keys:
            self.memories_list.insert("end", name)

    def choose_memory(self, _=None):
        choice = self.memories_list.curselection()
        if not choice:
            return
        key = self.memory_keys[choice[0]]
        self.memory_key.delete(0, "end")
        self.memory_key.insert(0, key)
        self.memory_value.delete(0, "end")
        self.memory_value.insert(0, self.state_data["memory"].get(key, ""))

    def save_memory(self):
        key = self.memory_key.get().strip()[:80]
        value = self.memory_value.get().strip()[:2000]
        if not key or not value:
            messagebox.showerror("Memory", "Enter a name and a value.")
            return
        self.state_data.setdefault("memory", {})[key] = value
        self.persist()
        self.refresh_memories()

    def delete_memory(self):
        key = self.memory_key.get().strip()
        if key in self.state_data.get("memory", {}) and messagebox.askyesno("Delete memory", f"Delete '{key}'?"):
            del self.state_data["memory"][key]
            self.persist()
            self.refresh_memories()
            self.memory_key.delete(0, "end")
            self.memory_value.delete(0, "end")

    def build_screen(self):
        ttk.Label(self.screen_tab, text="One-time screen capture only; never continuous monitoring.").pack(pady=10)
        line = ttk.Frame(self.screen_tab)
        line.pack(pady=5)
        self.button(line, "Capture desktop", self.capture_screen).pack(side="left", padx=5)
        self.button(line, "Analyze screenshot with cloud AI", self.analyze_screen).pack(side="left", padx=5)
        self.screen_image_label = ttk.Label(self.screen_tab, text="No screenshot captured")
        self.screen_image_label.pack(fill="both", expand=True, pady=10)
        self.screen_prompt = ttk.Entry(self.screen_tab)
        self.screen_prompt.insert(0, "Describe the screen and identify any error messages.")
        self.screen_prompt.pack(fill="x", padx=10, pady=10)

    def capture_screen(self):
        if not messagebox.askyesno("Screen permission", "Capture your desktop once and show a local preview?"):
            return
        try:
            image = ImageGrab.grab()
            image.thumbnail((1024, 768))
            self.screenshot = image.copy().convert("RGB")
            preview = self.screenshot.copy()
            preview.thumbnail((700, 420))
            self.preview_photo = ImageTk.PhotoImage(preview)
            self.screen_image_label.configure(image=self.preview_photo, text="")
        except Exception as exc:
            messagebox.showerror("Screen", str(exc))

    def analyze_screen(self):
        if self.screenshot is None:
            messagebox.showinfo("Screen", "Capture a screenshot first.")
            return
        if not messagebox.askyesno("Upload screenshot?", "Send this screenshot to the configured AI provider? "
                                                          "It may contain private information."):
            return
        self.vision_request(self.screenshot.copy(), self.screen_prompt.get())

    def vision_request(self, image, question):
        endpoint = self.endpoint.get().strip()
        if not endpoint.endswith("/v1/chat/completions"):
            messagebox.showerror("Vision", "Configure a trusted XAI /v1/chat/completions endpoint first.")
            return
        def work():
            output = io.BytesIO()
            image.save(output, format="JPEG", quality=72)
            uri = "data:image/jpeg;base64," + base64.b64encode(output.getvalue()).decode("ascii")
            response = self.request_json(endpoint.removesuffix("/v1/chat/completions") + "/v1/vision/analyze",
                                         {"image": uri, "question": question[:300]})
            return response.get("answer", "No vision response.")
        self.run_async(work, lambda answer: self.show_result("Vision result", answer), "Vision")

    def show_result(self, title, value):
        self.append_chat("assistant", value)
        self.tabs.select(self.chat_tab)

    def build_files(self):
        ttk.Label(self.files_tab, text="Explicitly select an UTF-8 text file. Every edit requires approval and creates a backup.").pack(pady=8)
        row = ttk.Frame(self.files_tab)
        row.pack(fill="x")
        self.file_label = ttk.Label(row, text="No file selected")
        self.file_label.pack(side="left", fill="x", expand=True, padx=8)
        self.button(row, "Open file", self.open_file).pack(side="left")
        self.file_editor = scrolledtext.ScrolledText(self.files_tab, bg="#121c31", fg="#e4edff",
                                                     insertbackground="white", wrap="none",
                                                     font=("Consolas", 11))
        self.file_editor.pack(fill="both", expand=True, pady=10)
        actions = ttk.Frame(self.files_tab)
        actions.pack(fill="x")
        self.button(actions, "Save edit (with backup)", self.save_file).pack(side="left", padx=5)
        self.button(actions, "AI suggest edit", self.ai_edit).pack(side="left", padx=5)
        self.file_instruction = ttk.Entry(actions)
        self.file_instruction.insert(0, "Fix obvious bugs while preserving behavior.")
        self.file_instruction.pack(side="left", fill="x", expand=True, padx=8)

    def open_file(self):
        path = filedialog.askopenfilename(title="Select an UTF-8 text file")
        if not path:
            return
        try:
            file = Path(path).resolve(strict=True)
            if not file.is_file() or file.stat().st_size > MAX_FILE:
                raise ValueError("Only text files below 200 KB are supported")
            data = file.read_text(encoding="utf-8")
        except (UnicodeError, OSError, ValueError) as exc:
            messagebox.showerror("Read file", str(exc))
            return
        self.current_file = file
        self.file_original = data
        self.file_label.configure(text=str(file))
        self.file_editor.delete("1.0", "end")
        self.file_editor.insert("1.0", data)

    def save_file(self):
        path = self.current_file
        if not path:
            messagebox.showinfo("File", "Select a file first.")
            return
        proposed = self.file_editor.get("1.0", "end-1c")
        if len(proposed.encode("utf-8")) > MAX_FILE:
            messagebox.showerror("File", "Text exceeds 200 KB")
            return
        try:
            actual = path.read_text(encoding="utf-8")
            if actual != self.file_original:
                messagebox.showerror("Conflict", "File changed on disk. Reopen it before saving.")
                return
            if not messagebox.askyesno("Approve write", f"Save changes to:\n{path}\n\n"
                                                       "A timestamped .bak copy will be created first."):
                return
            suffix = datetime.now().strftime("%Y%m%d-%H%M%S")
            backup = path.with_name(path.name + "." + suffix + ".bak")
            shutil.copy2(path, backup)
            temp = path.with_name(path.name + ".xai.tmp")
            try:
                temp.write_text(proposed, encoding="utf-8")
                os.replace(temp, path)
            finally:
                if temp.exists():
                    temp.unlink()
            self.file_original = proposed
            messagebox.showinfo("File saved", "Saved. Backup: " + str(backup))
        except (OSError, UnicodeError) as exc:
            messagebox.showerror("File", str(exc))

    def ai_edit(self):
        if self.current_file is None:
            messagebox.showinfo("AI editing", "Open a text file first.")
            return
        if not self.endpoint.get().strip():
            messagebox.showinfo("AI editing", "Set an authenticated cloud endpoint in Settings.")
            return
        content = self.file_editor.get("1.0", "end-1c")
        if len(content) > 30_000:
            messagebox.showerror("AI editing", "Select a smaller file (maximum 30,000 characters).")
            return
        if not messagebox.askyesno("Upload file to AI?", "Send the current file text to your configured AI provider "
                                                       "for a proposed edit? You must approve any saved changes."):
            return
        instruction = self.file_instruction.get().strip()[:300] or "Improve this file."
        def work():
            return self.chat_request([
                {"role": "system", "content": "Return ONLY the full revised file content. No markdown fences. "
                                              "Preserve unspecified behavior. No destructive changes."},
                {"role": "user", "content": instruction + "\n\nFILE CONTENT:\n" + content}
            ])
        def on_answer(text):
            if messagebox.askyesno("Review AI proposal", "An AI edit has been generated. "
                                                        "Place it into the editor for manual review? "
                                                        "This will NOT save it to disk."):
                self.file_editor.delete("1.0", "end")
                self.file_editor.insert("1.0", text)
        self.run_async(work, on_answer, "AI editing")

    def build_settings(self):
        frame = self.settings_tab
        ttk.Label(frame, text="Cloud connection (optional, unconfigured by default)",
                  font=("Segoe UI", 12, "bold")).pack(pady=10)
        def field(label, initial, secret=False):
            ttk.Label(frame, text=label).pack(anchor="w", padx=22)
            var = tk.StringVar(value=initial)
            ttk.Entry(frame, textvariable=var, show="*" if secret else "").pack(fill="x", padx=22, pady=4)
            return var
        self.endpoint = field("Trusted HTTPS /v1/chat/completions endpoint", self.state_data.get("endpoint", ""))
        self.model = field("Model name (optional)", self.state_data.get("model", ""))
        self.api_key = field("API key (session only; NEVER saved to disk)", "", secret=True)
        self.share_memory = tk.BooleanVar(value=bool(self.state_data.get("share_memory", False)))
        ttk.Checkbutton(frame, text="Include local memories in cloud requests",
                        variable=self.share_memory).pack(anchor="w", padx=20, pady=6)
        self.humor = tk.IntVar(value=int(self.state_data.get("humor", 60)))
        self.sarcasm = tk.IntVar(value=int(self.state_data.get("sarcasm", 55)))
        for label, var in [("Humor", self.humor), ("Sarcasm", self.sarcasm)]:
            ttk.Label(frame, text=label).pack(anchor="w", padx=22)
            ttk.Scale(frame, from_=0, to=100, variable=var).pack(fill="x", padx=24)
        ttk.Label(frame, text="Custom personality instructions").pack(anchor="w", padx=22, pady=(10, 0))
        self.custom_instructions = tk.Text(frame, height=4, bg="#121c31", fg="#e4edff",
                                           insertbackground="white", wrap="word")
        self.custom_instructions.pack(fill="x", padx=22)
        self.custom_instructions.insert("1.0", self.state_data.get("custom_instructions", ""))
        self.button(frame, "Save settings", self.save_settings).pack(pady=14)

    def save_settings(self):
        endpoint = self.endpoint.get().strip()
        if endpoint:
            try:
                safe_https(endpoint)
            except ValueError as exc:
                messagebox.showerror("Settings", str(exc))
                return
        self.state_data.update({
            "endpoint": endpoint,
            "model": self.model.get().strip()[:160],
            "share_memory": self.share_memory.get(),
            "humor": int(self.humor.get()),
            "sarcasm": int(self.sarcasm.get()),
            "custom_instructions": self.custom_instructions.get("1.0", "end").strip()[:1500]
        })
        self.persist()
        messagebox.showinfo("Settings", "Settings saved locally. API key retained only for this session.")

    def close(self):
        self.save_on_exit()
        self.destroy()

    def save_on_exit(self):
        self.state_data.update({
            "endpoint": self.endpoint.get().strip(),
            "model": self.model.get().strip()[:160],
            "share_memory": self.share_memory.get(),
            "humor": int(self.humor.get()),
            "sarcasm": int(self.sarcasm.get()),
            "custom_instructions": self.custom_instructions.get("1.0", "end").strip()[:1500]
        })
        self.persist()


if __name__ == "__main__":
    XAI().mainloop()
