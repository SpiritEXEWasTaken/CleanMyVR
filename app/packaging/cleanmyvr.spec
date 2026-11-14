# PyInstaller spec for CleanMyVR.exe (one file, windowed). Build from app/:
#   pyinstaller packaging/cleanmyvr.spec
# Put launcher.apk in app/assets/ first to bundle the launcher.
import os
import sys

block_cipher = None
here = os.path.abspath(os.path.join(SPECPATH, ".."))   # the app/ folder
datas = [
    (os.path.join(here, "cleanmyvr", "devices"), "cleanmyvr/devices"),
    (os.path.join(here, "cleanmyvr", "gui", "web"), "cleanmyvr/gui/web"),
]
apk = os.path.join(here, "assets", "launcher.apk")
if os.path.exists(apk):
    datas.append((apk, "assets"))

a = Analysis([os.path.join(here, "packaging", "entry.py")], pathex=[here], datas=datas,
             hiddenimports=(["webview.platforms.edgechromium", "clr_loader"] if sys.platform == "win32" else []),
             excludes=["tkinter"], cipher=block_cipher)
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)
exe = EXE(pyz, a.scripts, a.binaries, a.zipfiles, a.datas, name="CleanMyVR",
          console=False, upx=False, icon=None)
