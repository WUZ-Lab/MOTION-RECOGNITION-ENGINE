"""Keep arithmetic, channel order and causal resampling in sync with core/Features.kt."""
import math
from collections import deque

CLASSES = ["squat", "burpee", "jump_squat", "push_up", "other"]
FEATURE_COUNT = 32
WINDOW_SIZE = 180
SAMPLE_RATE = 30
REQUIRED = (11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)


def validate_pose(frame):
    if not isinstance(frame["timestampMs"], int) or frame["timestampMs"] < 0:
        raise ValueError("Invalid timestamp")
    if frame["imageWidth"] <= 0 or frame["imageHeight"] <= 0:
        raise ValueError("Invalid image dimensions")
    for name in ("landmarks", "worldLandmarks"):
        if len(frame[name]) != 33:
            raise ValueError("Expected 33 landmarks")
        for p in frame[name]:
            if not all(math.isfinite(p[k]) for k in ("x", "y", "z")) or not 0 <= p.get("visibility", 1) <= 1:
                raise ValueError("Invalid coordinate/visibility")


def midpoint(a, b):
    return {k: (a[k] + b[k]) / 2 for k in ("x", "y", "z")}


def distance(a, b):
    return math.sqrt(sum((a[k] - b[k]) ** 2 for k in ("x", "y", "z")))


def angle(a, b, c):
    ab, cb = distance(a, b), distance(c, b)
    if min(ab, cb) < 1e-5:
        return None
    dot = sum((a[k] - b[k]) * (c[k] - b[k]) for k in ("x", "y", "z"))
    return math.acos(max(-1, min(1, dot / (ab * cb)))) / math.pi


def base_features(frame):
    validate_pose(frame)
    w, n = frame["worldLandmarks"], frame["landmarks"]
    if any(min(w[i].get("visibility", 1), n[i].get("visibility", 1)) < .5 for i in REQUIRED):
        return None
    hip, shoulder = midpoint(w[23], w[24]), midpoint(w[11], w[12])
    torso = distance(hip, shoulder)
    nh, ns = midpoint(n[23], n[24]), midpoint(n[11], n[12])
    aspect = frame["imageWidth"] / frame["imageHeight"]
    scale = math.hypot((ns["x"] - nh["x"]) * aspect, ns["y"] - nh["y"])
    if torso < 1e-4 or scale < 1e-4:
        return None
    result = [angle(w[a], w[b], w[c]) for a, b, c in
              ((23, 25, 27), (24, 26, 28), (11, 23, 25), (12, 24, 26), (11, 13, 15), (12, 14, 16))]
    if any(v is None for v in result):
        return None
    result.extend([
        math.acos(max(-1, min(1, (hip["y"] - shoulder["y"]) / torso))) / math.pi,
        distance(w[15], hip) / torso, distance(w[16], hip) / torso,
        distance(w[27], w[28]) / torso, nh["y"], (n[27]["y"] + n[28]["y"]) / 2, scale,
        ((w[15]["y"] + w[16]["y"]) / 2 - hip["y"]) / torso,
        ((w[27]["y"] + w[28]["y"]) / 2 - hip["y"]) / torso,
        distance(hip, midpoint(w[27], w[28])) / torso,
    ])
    return result if all(math.isfinite(v) for v in result) else None


class FeatureExtractor:
    def __init__(self):
        self.previous = None

    def reset(self):
        self.previous = None

    def extract(self, frame):
        base = base_features(frame)
        if base is None:
            self.reset()
            return None
        derivatives = [0.] * 16
        if self.previous:
            last_time, last_values = self.previous
            dt = (frame["timestampMs"] - last_time) / 1000
            if 0 < dt <= .5:
                derivatives = [max(-20, min(20, (base[i] - last_values[i]) / dt)) for i in range(16)]
        self.previous = (frame["timestampMs"], base)
        return base + derivatives


class TemporalWindow:
    def __init__(self, mean=None, std=None):
        self.mean = mean if mean is not None else [0.] * 32
        self.std = std if std is not None else [1.] * 32
        self.reset()

    def reset(self):
        self.rows = deque(maxlen=WINDOW_SIZE)
        self.origin = None
        self.tick = 0
        self.previous = None
        self.previous_time = 0

    def add(self, timestamp, features):
        if self.origin is None:
            self.origin = timestamp
        latest_tick = (timestamp - self.origin) * SAMPLE_RATE // 1000
        if latest_tick - self.tick > WINDOW_SIZE:
            self.rows.clear()
            self.tick = latest_tick - WINDOW_SIZE + 1
        while self.origin + self.tick * 1000 // SAMPLE_RATE <= timestamp:
            at = self.origin + self.tick * 1000 // SAMPLE_RATE
            source = features if at == timestamp else self.previous if at - self.previous_time <= 100 else None
            row = [(source[i] - self.mean[i]) / self.std[i] for i in range(32)] + [1.] if source is not None else [0.] * 33
            self.rows.append(row)
            self.tick += 1
        self.previous, self.previous_time = features, timestamp

    def snapshot(self):
        return [[0.] * 33 for _ in range(WINDOW_SIZE - len(self.rows))] + list(self.rows)


def feature_stream(records):
    """Same gap reset and invalid-pose derivative handling as MotionEngine."""
    extractor = FeatureExtractor()
    window = TemporalWindow()
    last_good = None
    for record in records:
        timestamp = record["timestampMs"]
        if last_good is not None and timestamp - last_good > 500:
            extractor.reset()
            window.reset()
            last_good = None
        pose = record.get("pose")
        values = extractor.extract(pose) if pose is not None else None
        if pose is None:
            extractor.reset()
        if values is not None:
            last_good = timestamp
        window.add(timestamp, values)
        yield timestamp, values, window.snapshot()
