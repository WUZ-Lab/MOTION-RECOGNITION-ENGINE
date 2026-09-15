import copy
import json
import tempfile
import unittest
from pathlib import Path
from motion_training.features import FeatureExtractor, TemporalWindow, feature_stream
from motion_training.data import split_participants, read_session, read_annotations
from motion_training.evaluate import evaluate

FIXTURE = Path(__file__).resolve().parents[2] / "fixtures" / "pose-features-v1.json"


class FeatureTests(unittest.TestCase):
    def test_golden_feature_and_causal_window_parity(self):
        fixture = json.loads(FIXTURE.read_text())
        extractor, window = FeatureExtractor(), TemporalWindow()
        for case in fixture["cases"]:
            actual = extractor.extract(case["pose"])
            for a, b in zip(actual, case["expectedFeatures"]):
                self.assertAlmostEqual(a, b, places=5)
            window.add(case["pose"]["timestampMs"], actual)
        self.assertEqual(fixture["expectedWindow"], window.snapshot())

    def test_gaps_do_not_create_motion_derivatives(self):
        frames = json.loads(FIXTURE.read_text())["cases"]
        a = copy.deepcopy(frames[0]["pose"])
        b = copy.deepcopy(frames[2]["pose"]); b["timestampMs"] = 1000
        rows = list(feature_stream([{"timestampMs": 0, "pose": a}, {"timestampMs": 1000, "pose": b}]))
        self.assertEqual(rows[-1][1][16:], [0.] * 16)
        self.assertEqual(sum(r[-1] for r in rows[-1][2]), 1)

    def test_low_visibility_and_nonfinite_coordinates(self):
        frame = json.loads(FIXTURE.read_text())["cases"][0]["pose"]
        frame["landmarks"][25]["visibility"] = .1
        self.assertIsNone(FeatureExtractor().extract(frame))
        frame["landmarks"][0]["x"] = float("nan")
        with self.assertRaises(ValueError):
            FeatureExtractor().extract(frame)


class DataTests(unittest.TestCase):
    def test_split_is_deterministic_and_participants_never_leak(self):
        ids = [f"person-{i}" for i in range(20)]
        a = split_participants(ids + ids)
        self.assertEqual(a, split_participants(list(reversed(ids))))
        self.assertEqual(set(a.values()), {"train", "validation", "test"})
        self.assertEqual(len(a), len(ids))
        with self.assertRaises(ValueError):
            split_participants(["a", "a", "b"])

    def test_annotation_mismatch_and_overlap_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "a.json"
            data = {"schemaVersion": 1, "sessionId": "a", "repetitions": [
                {"startMs": 0, "endMs": 1000, "exerciseId": "squat", "phases": {"start": 0, "bottom": 500, "end": 1000}},
                {"startMs": 900, "endMs": 2000, "exerciseId": "other"}]}
            path.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                read_annotations(path, {"sessionId": "a"}, [{"timestampMs": 0}, {"timestampMs": 2000}])

    def test_timestamp_mismatch_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            pose = json.loads(FIXTURE.read_text())["cases"][0]["pose"]
            path = Path(directory) / "a.jsonl"
            path.write_text(json.dumps({"type": "session", "schemaVersion": 1, "sessionId": "a", "participantId": "p"}) + "\n" +
                            json.dumps({"type": "frame", "timestampMs": 100, "pose": pose}) + "\n")
            with self.assertRaises(ValueError):
                read_session(path)

    def test_missing_predictions_are_not_perfect_progress(self):
        annotations = {"repetitions": [{"startMs": 0, "endMs": 1000, "exerciseId": "squat", "phases": {"start": 0, "bottom": 500, "end": 1000}}]}
        predictions = [{"timestampMs": t, "status": "UNCERTAIN", "progress": None} for t in range(0, 1001, 100)]
        report = evaluate(annotations, predictions)
        self.assertEqual(report["missedReps"], 1)
        self.assertEqual(report["progressCoverage"], 0)
        self.assertIsNone(report["progressMae"])


if __name__ == "__main__":
    unittest.main()
