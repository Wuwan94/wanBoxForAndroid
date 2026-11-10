#!/usr/bin/env bash
set -euo pipefail
# Explicit pin updates are code-reviewed together with the source and digest.
python3 tools/prepare_geo_assets.py
