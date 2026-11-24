#!/usr/bin/env python3
"""Validate the App merge fragment and print an upstream check input.

The unmodified upstream CLI has no XHTTP inbound registration. It checks the
standard VLESS fields; TestVLESSXHTTPInboundExample checks the full expanded
configuration through the same registry as wanBox.
"""
import json
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "docs/config/xhttp-inbound.json"
fragment = json.loads(path.read_text(encoding="utf-8"))
if set(fragment) != {"inbounds+"} or not isinstance(fragment["inbounds+"], list):
    raise SystemExit("expected only an inbounds+ array (append, not replace)")
if len(fragment["inbounds+"]) != 1:
    raise SystemExit("expected one XHTTP example")
inbound = fragment["inbounds+"][0]
if inbound.get("type") != "vless" or inbound.get("listen") != "127.0.0.1":
    raise SystemExit("expected a loopback-only VLESS example")
transport = inbound.pop("transport", {})
if transport.get("type") != "xhttp":
    raise SystemExit("expected XHTTP transport")
print(json.dumps({"inbounds": [inbound]}))
