#!/usr/bin/env python3
"""
Argos Translate persistent bridge for Image & Video Viewer.

Reads one JSON request per line from stdin, writes one JSON response per line
to stdout.  Stays alive for the lifetime of the Java process.

Commands
--------
  {"cmd":"ping"}
  {"cmd":"detect",       "text":"..."}
  {"cmd":"translate",    "from":"en","to":"fr","text":"..."}
  {"cmd":"translate_list","from":"en","to":"fr","texts":["...","..."]}
  {"cmd":"list_installed"}
  {"cmd":"list_available"}
  {"cmd":"install",      "from":"en","to":"fr"}

Dependencies
------------
  pip install argostranslate
  pip install langdetect   # optional – enables auto-detect
"""

import sys
import io
import json
import traceback

# Force UTF-8 on Windows where stdout/stdin default to cp1252.
# Without this, characters like ñ é ü ç are mangled to '?' in the JSON stream.
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
sys.stdin  = io.TextIOWrapper(sys.stdin.buffer,  encoding='utf-8', errors='replace')


def respond(obj):
    print(json.dumps(obj, ensure_ascii=False), flush=True)


def handle(req):
    cmd = req.get("cmd", "")

    # ── ping ──────────────────────────────────────────────────────────────────
    if cmd == "ping":
        respond({"result": "pong"})

    # ── detect language ───────────────────────────────────────────────────────
    elif cmd == "detect":
        text = req.get("text", "")
        try:
            from langdetect import detect
            respond({"result": detect(text)})
        except ImportError:
            respond({"result": "unknown",
                     "note": "langdetect not installed; run: pip install langdetect"})
        except Exception as e:
            respond({"result": "unknown", "error": str(e)})

    # ── translate single string ───────────────────────────────────────────────
    elif cmd == "translate":
        import argostranslate.translate
        result = argostranslate.translate.translate(
            req.get("text", ""), req.get("from", "en"), req.get("to", "fr"))
        respond({"result": result})

    # ── translate list (batch) ────────────────────────────────────────────────
    elif cmd == "translate_list":
        import argostranslate.translate
        from_code = req.get("from", "en")
        to_code   = req.get("to",   "fr")
        texts     = req.get("texts", [])
        results   = [argostranslate.translate.translate(t, from_code, to_code)
                     for t in texts]
        respond({"result": results})

    # ── list installed language packs ─────────────────────────────────────────
    elif cmd == "list_installed":
        import argostranslate.package
        pkgs = argostranslate.package.get_installed_packages()
        respond({"result": [
            {"from": p.from_code, "to": p.to_code,
             "from_name": p.from_name, "to_name": p.to_name}
            for p in pkgs
        ]})

    # ── list available language packs (downloads index) ───────────────────────
    elif cmd == "list_available":
        import argostranslate.package
        argostranslate.package.update_package_index()
        available = argostranslate.package.get_available_packages()
        installed = {(p.from_code, p.to_code)
                     for p in argostranslate.package.get_installed_packages()}
        respond({"result": [
            {"from": p.from_code, "to": p.to_code,
             "from_name": p.from_name, "to_name": p.to_name,
             "installed": (p.from_code, p.to_code) in installed}
            for p in available
        ]})

    # ── install a language pack ───────────────────────────────────────────────
    elif cmd == "install":
        import argostranslate.package
        from_code = req.get("from")
        to_code   = req.get("to")
        argostranslate.package.update_package_index()
        available = argostranslate.package.get_available_packages()
        pkg = next((p for p in available
                    if p.from_code == from_code and p.to_code == to_code), None)
        if pkg is None:
            respond({"error": f"No package found for {from_code} → {to_code}"})
        else:
            path = pkg.download()
            argostranslate.package.install_from_path(path)
            respond({"result": "installed"})

    else:
        respond({"error": f"Unknown command: {cmd}"})


if __name__ == "__main__":
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            handle(json.loads(line))
        except json.JSONDecodeError as e:
            respond({"error": f"JSON parse error: {e}"})
        except Exception:
            respond({"error": traceback.format_exc()})
