"""python -m cleanmyvr            -> GUI (falls back to the command line if no GUI is available)
python -m cleanmyvr --cli      -> guided command line"""
import sys


def main() -> None:
    if "--cli" in sys.argv or "-c" in sys.argv:
        from .cli import main as cli
        cli()
        return
    try:
        from .gui import main as gui
    except ImportError as e:
        print(f"GUI unavailable ({e}); starting the command line instead.\n")
        from .cli import main as cli
        cli()
        return
    gui()


if __name__ == "__main__":
    main()
