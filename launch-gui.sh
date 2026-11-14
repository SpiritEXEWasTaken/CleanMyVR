#!/usr/bin/env bash
# CleanMyVR (window). macOS / Linux. First run sets up a private Python environment in .venv.
set -euo pipefail
cd "$(dirname "$0")"
PY=${PYTHON:-python3}
command -v "$PY" >/dev/null || { echo "Python 3.9+ is required (https://www.python.org/downloads/)"; exit 1; }

if [[ ! -x .venv/bin/python ]]; then
  echo "First run: setting up CleanMyVR (one-time, needs internet)…"
  if [[ $(uname) == Linux ]]; then
    # use the system's GTK WebKit (python3-gi / webkit2gtk) instead of building it
    "$PY" -m venv --system-site-packages .venv
  else
    "$PY" -m venv .venv
  fi
  .venv/bin/python -m pip install --quiet --upgrade pip
  .venv/bin/python -m pip install --quiet -r app/requirements.txt
fi

VPY="$PWD/.venv/bin/python"
cd app
if ! "$VPY" -c "import webview" 2>/dev/null; then
  echo "The window library isn't available; starting the guided command line instead."
  exec "$VPY" -m cleanmyvr --cli
fi
exec "$VPY" -m cleanmyvr "$@"
