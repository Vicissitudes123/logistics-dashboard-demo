"""Frozen and source entry point. No user's data is stored beside this executable."""
from anime_shelf.app import main

if __name__ == "__main__":
    raise SystemExit(main())
