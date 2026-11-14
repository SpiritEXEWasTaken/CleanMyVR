#!/usr/bin/env bash
# CleanMyVR (guided command line). macOS / Linux. Needs only Python 3.9+, no extra packages.
set -euo pipefail
cd "$(dirname "$0")/app"
PY=${PYTHON:-python3}
command -v "$PY" >/dev/null || { echo "Python 3.9+ is required (https://www.python.org/downloads/)"; exit 1; }
exec "$PY" -m cleanmyvr --cli "$@"
