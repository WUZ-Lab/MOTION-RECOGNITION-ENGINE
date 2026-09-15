"""Generate deterministic synthetic coordinates. Never a real exercise training dataset."""
import argparse
import copy
import json
import math
from pathlib import Path
from motion_training.features import CLASSES, FeatureExtractor, TemporalWindow


def pose(timestamp, knee=180, elbow=170, tilt=0, shift=0, scale=1):
    bend = math.radians(180 - knee)
    arm_bend = math.radians(180 - elbow)
    w = [{"x": 0., "y": -.7, "z": 0., "visibility": 1.} for _ in range(33)]
    for offset, side in ((0, -1), (1, 1)):
        for joint, point in {
            11: (side * .18, -.5, 0), 13: (side * .22, -.25, 0),
            15: (side * .22, -.25 + .25 * math.cos(arm_bend), .25 * math.sin(arm_bend)),
            23: (side * .15, 0, 0), 25: (side * .15, .4, 0),
            27: (side * .15, .4 + .4 * math.cos(bend), .4 * math.sin(bend)),
        }.items():
            w[joint + offset] = dict(zip(("x", "y", "z"), point), visibility=1.)
    rotation = math.radians(tilt)
    for p in w:
        y, z = p["y"], p["z"]
        p["y"] = (y * math.cos(rotation) - z * math.sin(rotation)) * scale
        p["z"] = (y * math.sin(rotation) + z * math.cos(rotation)) * scale
        p["x"] *= scale
    foot = (w[27]["y"] + w[28]["y"]) / 2
    n = [{"x": .5 + p["x"] * .5, "y": .9 - (foot - p["y"]) * .5 + shift, "z": p["z"] * .5, "visibility": 1.} for p in w]
    return {"timestampMs": timestamp, "imageWidth": 480, "imageHeight": 640, "landmarks": n, "worldLandmarks": w}


def golden(output):
    frames = [pose(0), pose(33, knee=130), pose(100, knee=95, elbow=110, tilt=20), pose(233, tilt=75), pose(266, shift=-.1)]
    extractor, window = FeatureExtractor(), TemporalWindow()
    cases = []
    for frame in frames:
        values = extractor.extract(frame)
        window.add(frame["timestampMs"], values)
        cases.append({"pose": frame, "expectedFeatures": values})
    low = copy.deepcopy(frames[-1]); low["timestampMs"] = 300; low["landmarks"][25]["visibility"] = .1
    assert extractor.extract(low) is None
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"schemaVersion": 1, "cases": cases, "expectedWindow": window.snapshot()}, indent=2) + "\n")


def smoke(output):
    output.mkdir(parents=True, exist_ok=True)
    for participant in range(3):
        for exercise in CLASSES:
            session = f"synthetic-{participant}-{exercise}"
            records = []
            for i in range(240):
                t = i * 1000 // 30
                cycle = max(0., min(1., (t - 1000) / 5000))
                depth = math.sin(cycle * math.pi)
                knee, elbow, tilt, shift = 175, 170, 10, 0
                if exercise in ("squat", "jump_squat"):
                    knee = 175 - 85 * depth
                    if exercise == "jump_squat" and .75 <= cycle <= .9:
                        shift = -.12 * math.sin((cycle - .75) / .15 * math.pi)
                elif exercise == "push_up":
                    elbow = 175 - 85 * depth
                    tilt = 85
                elif exercise == "burpee":
                    tilt = 10 + 75 * depth
                    knee = 175 - 60 * abs(math.sin(cycle * 2 * math.pi))
                    if .85 <= cycle <= .95:
                        shift = -.12
                elif exercise == "other":
                    knee = 155 + 15 * math.sin(t / 250)
                    tilt = 30 + 10 * math.sin(t / 300)
                frame = pose(t, knee, elbow, tilt, shift, 1 + participant * .05)
                records.append({"type": "frame", "timestampMs": t, "pose": frame})
            header = {"type": "session", "schemaVersion": 1, "sessionId": session, "participantId": f"synthetic-{participant}",
                      "exerciseLabel": exercise, "cameraView": "fixed_oblique", "poseModel": "synthetic", "poseSettings": {"synthetic": "true"}}
            path = output / f"{session}.jsonl"
            path.write_text("\n".join(json.dumps(r, separators=(",", ":")) for r in [header] + records) + "\n")
            repetitions = []
            if exercise != "other":
                phases = {"start": 1000, "bottom": 3500, "end": 6000}
                if exercise == "jump_squat":
                    phases.update(takeoff=4750, landing=5500)
                elif exercise == "burpee":
                    phases = {"start": 1000, "lowered": 2000, "plank": 3000, "tucked": 4000, "takeoff": 5250, "landing": 5750, "end": 6000}
                repetitions = [{"startMs": 1000, "endMs": 6000, "exerciseId": exercise, "valid": True, "phases": phases}]
            path.with_suffix(".annotations.json").write_text(json.dumps({"schemaVersion": 1, "sessionId": session, "repetitions": repetitions}, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--golden", type=Path)
    parser.add_argument("--smoke-data", type=Path)
    args = parser.parse_args()
    if args.golden:
        golden(args.golden)
    if args.smoke_data:
        smoke(args.smoke_data)
