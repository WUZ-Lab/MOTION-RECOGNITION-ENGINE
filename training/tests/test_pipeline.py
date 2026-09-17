import copy
import json
import tempfile
import unittest
from pathlib import Path
from motion_training.features import FeatureExtractor, TemporalWindow, feature_stream
from motion_training.data import split_participants, read_session, read_annotations, label_at, validate_classes
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
        self.assertEqual(report["missingPhaseBoundaries"], 3)
        self.assertEqual(report["phaseBoundaryCoverage"], 0)

    def test_incomplete_exercise_keeps_activity_identity_but_is_not_a_completed_rep(self):
        annotations = {"repetitions": [{"startMs": 0, "endMs": 1000, "exerciseId": "squat", "valid": False}]}
        self.assertEqual(label_at(500, annotations), "squat")
        self.assertEqual(label_at(1001, annotations), "other")
        self.assertEqual(evaluate(annotations, [])["expectedReps"], 0)

    def test_selected_output_class_order_is_preserved(self):
        self.assertEqual(validate_classes(["other", "squat"]), ["other", "squat"])
        for invalid in (["squat"], ["squat", "push_up"], ["squat", "squat", "other"], ["unknown", "other"]):
            with self.assertRaises(ValueError):
                validate_classes(invalid)


class EventEvaluationTests(unittest.TestCase):
    def rep(self, start=0, end=1000):
        return {"startMs": start, "endMs": end, "exerciseId": "squat",
                "phases": {"start": start, "bottom": (start+end)//2, "end": end}}

    def event(self, rep_id=1, start=0, end=1000, emitted=1500):
        return {"repId": rep_id, "exerciseId": "squat", "startedAtMs": start, "endedAtMs": end, "emittedAtMs": emitted}

    def test_event_delay_does_not_pollute_live_labels_or_phase_end_error(self):
        predictions = [{"timestampMs": t, "status": "TRACKING", "exerciseId": "squat", "events": [],
                        "current": {"status": "TRACKING", "exerciseId": "squat", "phase": phase}}
                       for t, phase in [(0, "lowering"), (500, "bottom"), (1000, "complete")]]
        predictions.append({"timestampMs": 1500, "repCompleted": True, "exerciseId": "squat", "status": "TRACKING",
                            "current": {"status": "OTHER", "exerciseId": None}, "events": [self.event()]})
        report = evaluate({"repetitions": [self.rep()]}, predictions)
        self.assertEqual(report["matchedReps"], 1)
        self.assertEqual(report["frameLabelAccuracy"], 1)
        self.assertEqual(report["phaseBoundaryMaeMs"], 0)
        self.assertEqual(report["confirmationDelayP95Ms"], 500)
        self.assertEqual(report["phaseBoundaryCoverage"], 1)

    def test_multiple_events_in_one_frame_are_counted_and_duplicates_rejected(self):
        annotations = {"repetitions": [self.rep(), self.rep(1100, 2100)]}
        events = [self.event(emitted=2500), self.event(2, 1100, 2100, 2500)]
        predictions = [{"timestampMs": 2500, "current": {"status": "OTHER"}, "events": events}]
        report = evaluate(annotations, predictions)
        self.assertEqual(report["matchedReps"], 2)
        self.assertEqual(report["missingPhaseBoundaries"], 2)
        with self.assertRaises(ValueError):
            evaluate(annotations, predictions + predictions)

    def test_wrong_cycle_start_does_not_match_by_emission_time_alone(self):
        p = {"timestampMs": 1500, "current": {"status": "OTHER"}, "events": [self.event(start=600)]}
        report = evaluate({"repetitions": [self.rep()]}, [p])
        self.assertEqual(report["missedReps"], 1)
        self.assertEqual(report["falseCompletions"], 1)

    def test_phase_error_uses_transition_not_nearest_frame_of_a_hold(self):
        predictions = [{"timestampMs": t, "exerciseId": "squat", "phase": "bottom", "status": "TRACKING"}
                       for t in (400, 500, 600)]
        report = evaluate({"repetitions": [self.rep()]}, predictions)
        self.assertEqual(report["phaseBoundaryMaeMs"], 100)
        self.assertEqual(report["missingPhaseBoundaries"], 2)

    def test_legacy_completion_predictions_remain_supported(self):
        report = evaluate({"repetitions": [self.rep()]}, [{"timestampMs": 1500, "exerciseId": "squat",
                          "repCompleted": True, "status": "TRACKING", "phase": "complete"}])
        self.assertEqual(report["matchedReps"], 1)
        self.assertIsNone(report["confirmationDelayP50Ms"])

    def test_guided_selection_is_not_scored_as_automatic_classification(self):
        report = evaluate({"repetitions": []}, [{"timestampMs": 0, "current": {
            "exerciseId": "squat", "source": "GUIDED", "status": "TRACKING"}, "events": []}])
        self.assertIsNone(report["frameLabelAccuracy"])
        self.assertIsNone(report["recognizedRepCoverage"])
        self.assertEqual(report["classificationFrameCoverage"], 0)


if __name__ == "__main__":
    unittest.main()
