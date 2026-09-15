"""Download Google's versioned MediaPipe lite pose model for import into the sample app."""
import argparse
import hashlib
import urllib.request
from pathlib import Path

URL = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task"

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(URL, timeout=60) as response:
        data = response.read(32 * 1024 * 1024 + 1)
    if not data or len(data) > 32 * 1024 * 1024:
        raise ValueError("Unexpected model size")
    args.output.write_bytes(data)
    print(f"{args.output}: {len(data)} bytes, sha256={hashlib.sha256(data).hexdigest()}")
