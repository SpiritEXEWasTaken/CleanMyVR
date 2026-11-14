"""PyInstaller entry point: CleanMyVR.exe opens the window; CleanMyVR.exe --cli runs the guided command line."""
import os
import sys

if "--cli" in sys.argv and getattr(sys, "frozen", False) and os.name == "nt":
    # The EXE is built as a windowed app; give the command line a console of its own.
    import ctypes
    if not ctypes.windll.kernel32.GetConsoleWindow():
        ctypes.windll.kernel32.AllocConsole()
        sys.stdin = open("CONIN$", "r")
        sys.stdout = open("CONOUT$", "w")
        sys.stderr = sys.stdout

from cleanmyvr.__main__ import main

main()
