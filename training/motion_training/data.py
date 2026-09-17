import hashlib
import json
from pathlib import Path
from .features import CLASSES, feature_stream, validate_pose

PHASES = {
    "squat": ("start", "bottom", "end"),
    "push_up": ("start", "bottom", "end"),
    "jump_squat": ("start", "bottom", "takeoff", "landing", "end"),
    "burpee": ("start", "lowered", "plank", "tucked", "takeoff", "landing", "end"),
}


def read_session(path):
    path = Path(path)
    with path.open() as source:
        header = json.loads(next(source))
        if header.get("type") != "session" or header.get("schemaVersion") != 1:
            raise ValueError(f"Unsupported session: {path}")
        if not header.get("participantId", "").strip() or not header.get("sessionId", "").strip():
            raise ValueError("Participant and session IDs are required")
        records = []
        last = -1
        for line in source:
            record = json.loads(line)
            timestamp = record["timestampMs"]
            if record.get("type") != "frame" or not isinstance(timestamp, int) or timestamp <= last:
                raise ValueError("Frame timestamps must be strictly increasing")
            pose = record.get("pose")
            if pose is not None:
                validate_pose(pose)
                if pose["timestampMs"] != timestamp:
                    raise ValueError("Pose and record timestamps differ")
            last = timestamp
            records.append(record)
    if not records:
        raise ValueError("Session contains no frames")
    return header, records


def read_annotations(path, header, records):
    data = json.loads(Path(path).read_text())
    if data.get("schemaVersion") != 1 or data.get("sessionId") != header["sessionId"]:
        raise ValueError("Annotation session/version mismatch")
    previous_end = -1
    for rep in data["repetitions"]:
        start, end = rep["startMs"], rep["endMs"]
        if not records[0]["timestampMs"] <= start < end <= records[-1]["timestampMs"] or start <= previous_end:
            raise ValueError("Annotations must be ordered, nonoverlapping and inside the session")
        if rep["exerciseId"] not in CLASSES or not isinstance(rep.get("valid", True), bool):
            raise ValueError("Unknown label/invalid validity flag")
        phases = rep.get("phases", {})
        if rep.get("valid", True) and rep["exerciseId"] != "other":
            expected = PHASES[rep["exerciseId"]]
            if set(phases) != set(expected):
                raise ValueError(f"Expected phases: {expected}")
            times = [phases[p] for p in expected]
            if times[0] != start or times[-1] != end or any(a >= b for a, b in zip(times, times[1:])):
                raise ValueError("Phase boundaries must be strictly ordered from start to end")
        previous_end = end
    return data


def label_at(timestamp, annotations):
    for rep in annotations["repetitions"]:
        if rep["startMs"] <= timestamp <= rep["endMs"]:
            # Activity identity and repetition validity are separate targets.
            return rep["exerciseId"]
    return "other"


def split_participants(ids, seed=42):
    ordered = sorted(set(ids), key=lambda p: hashlib.sha256(f"{seed}:{p}".encode()).hexdigest())
    if len(ordered) < 3:
        raise ValueError("Need at least 3 distinct participants for train/validation/test separation")
    n_test = max(1, round(len(ordered) * .15))
    n_val = max(1, round(len(ordered) * .15))
    return {p: "test" if i < n_test else "validation" if i < n_test + n_val else "train" for i, p in enumerate(ordered)}


def validate_classes(classes):
    classes = list(classes)
    if len(classes) < 2 or len(set(classes)) != len(classes) or "other" not in classes or not set(classes) <= set(CLASSES):
        raise ValueError("Select distinct supported classes including other and at least one exercise")
    return classes


def load_dataset(directory, seed=42, classes=CLASSES):
    import numpy as np
    classes = validate_classes(classes)
    sessions = []
    for path in sorted(Path(directory).glob("*.jsonl")):
        header, records = read_session(path)
        annotation_path = path.with_suffix(".annotations.json")
        if not annotation_path.exists():
            raise ValueError(f"Missing reviewed annotation file: {annotation_path}")
        annotations = read_annotations(annotation_path, header, records)
        sessions.append((path, header, records, annotations))
    if len({header["sessionId"] for _, header, _, _ in sessions}) != len(sessions):
        raise ValueError("Duplicate session IDs")
    split = split_participants([h["participantId"] for _, h, _, _ in sessions], seed)
    dataset = {name: {"x": [], "y": [], "refs": []} for name in ("train", "validation", "test")}
    for path, header, records, annotations in sessions:
        partition = dataset[split[header["participantId"]]]
        last_sample = -1000
        for timestamp, values, window in feature_stream(records):
            if values is None or timestamp - last_sample < 200 or sum(row[-1] for row in window) < 15:
                continue
            partition["x"].append(window)
            label = label_at(timestamp, annotations)
            partition["y"].append(classes.index(label if label in classes else "other"))
            partition["refs"].append({"session": path.name, "timestampMs": timestamp})
            last_sample = timestamp
    for name, partition in dataset.items():
        if not partition["x"]:
            raise ValueError(f"Empty {name} partition")
        partition["x"] = np.asarray(partition["x"], dtype=np.float32)
        partition["y"] = np.asarray(partition["y"], dtype=np.int32)
        missing = set(range(len(classes))) - set(partition["y"].tolist())
        if missing:
            raise ValueError(f"{name} lacks classes {[classes[i] for i in sorted(missing)]}; collect each selected exercise and other for each participant")
    valid = dataset["train"]["x"][:, :, -1] == 1
    values = dataset["train"]["x"][:, :, :-1][valid]
    mean, std = values.mean(axis=0), np.maximum(values.std(axis=0), 1e-3)
    for partition in dataset.values():
        x = partition["x"]
        x[:, :, :-1] = ((x[:, :, :-1] - mean) / std) * x[:, :, -1:]
    return dataset, mean, std, split
