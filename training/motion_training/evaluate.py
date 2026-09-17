"""Evaluate live tracking and repetition events separately, including legacy SDK predictions."""
import argparse
import json
import statistics
from pathlib import Path
from .data import read_session, read_annotations, label_at

ANCHORS = {
    "squat": [("start", 0), ("bottom", .5), ("end", 1)],
    "push_up": [("start", 0), ("bottom", .5), ("end", 1)],
    "jump_squat": [("start", 0), ("bottom", .4), ("takeoff", .7), ("landing", .7), ("end", 1)],
    "burpee": [("start", 0), ("lowered", .2), ("plank", .4), ("tucked", .6), ("takeoff", .8), ("landing", .8), ("end", 1)],
}
PHASE_ALIASES = {"bottom": {"bottom"}, "plank": {"plank"}, "takeoff": {"airborne"}, "landing": {"landing"},
                 "lowered": {"extending"}, "tucked": {"rising"}, "end": {"complete"}, "start": {"lowering"}}


def mean(values):
    return statistics.mean(values) if values else None


def percentile(values, fraction):
    if not values:
        return None
    values = sorted(values)
    at = (len(values) - 1) * fraction
    lo = int(at)
    return values[lo] + (values[min(lo + 1, len(values) - 1)] - values[lo]) * (at - lo)


def unpack_predictions(predictions):
    frames, events, ids = [], [], set()
    for p in predictions:
        frames.append({**p.get("current", p), "timestampMs": p["timestampMs"]})
        if "events" in p:
            for event in p["events"]:
                if event["repId"] in ids:
                    raise ValueError("Duplicate repetition event ID within a session")
                if not 0 <= event["startedAtMs"] < event["endedAtMs"] <= event["emittedAtMs"] == p["timestampMs"]:
                    raise ValueError("Invalid repetition event timestamps")
                ids.add(event["repId"])
                events.append(event)
        elif p.get("repCompleted"):
            events.append({"exerciseId": p["exerciseId"], "endedAtMs": p["timestampMs"], "emittedAtMs": p["timestampMs"]})
    return frames, events


def evaluate(annotations, predictions):
    reps = [r for r in annotations["repetitions"] if r.get("valid", True) and r["exerciseId"] != "other"]
    frames, events = unpack_predictions(predictions)
    matched_events = set()
    matched_by_rep = {}
    delays, completion_delays, confirmation_delays, phase_errors, progress_errors = [], [], [], [], []
    comparable_frames = total_rep_frames = expected_phases = 0
    transitions, previous = [], None
    for p in frames:
        identity = (p.get("exerciseId"), p.get("phase"))
        if identity != previous and identity[0] and identity[1]:
            transitions.append(p)
        previous = identity
    used_transitions = set()
    for rep_index, rep in enumerate(reps):
        matching = [p for p in frames if rep["startMs"] <= p["timestampMs"] <= rep["endMs"] and p.get("exerciseId") == rep["exerciseId"]]
        total_rep_frames += sum(rep["startMs"] <= p["timestampMs"] <= rep["endMs"] for p in frames)
        recognized = [p for p in matching if p.get("source") != "GUIDED"]
        if recognized:
            delays.append(recognized[0]["timestampMs"] - rep["startMs"])
        possible = []
        for i, event in enumerate(events):
            if i in matched_events or event.get("exerciseId") != rep["exerciseId"]:
                continue
            if "startedAtMs" in event:
                acceptable = (abs(event["startedAtMs"] - rep["startMs"]) <= 500 and
                              abs(event["endedAtMs"] - rep["endMs"]) <= 500 and
                              max(event["startedAtMs"], rep["startMs"]) < min(event["endedAtMs"], rep["endMs"]))
            else:
                acceptable = rep["endMs"] - 300 <= event["endedAtMs"] <= rep["endMs"] + 1500
            if acceptable:
                possible.append(i)
        event = None
        if possible:
            i = min(possible, key=lambda i: abs(events[i]["endedAtMs"] - rep["endMs"]))
            matched_events.add(i)
            matched_by_rep[rep_index] = i
            event = events[i]
            completion_delays.append(event["emittedAtMs"] - rep["endMs"])
            if "startedAtMs" in event:
                confirmation_delays.append(event["emittedAtMs"] - event["endedAtMs"])
        lower = max(rep["startMs"] - 300, (reps[rep_index-1]["endMs"] + rep["startMs"]) / 2 if rep_index else 0)
        upper = min(rep["endMs"] + 300, (rep["endMs"] + reps[rep_index+1]["startMs"]) / 2 if rep_index+1 < len(reps) else float("inf"))
        for phase, expected in rep.get("phases", {}).items():
            expected_phases += 1
            if event and "startedAtMs" in event and phase in ("start", "end"):
                phase_errors.append(abs(event["startedAtMs" if phase == "start" else "endedAtMs"] - expected))
                continue
            found = [i for i, p in enumerate(transitions) if i not in used_transitions and p.get("exerciseId") == rep["exerciseId"] and
                     lower <= p["timestampMs"] <= upper and p.get("phase") in PHASE_ALIASES.get(phase, {phase})]
            if found:
                i = min(found, key=lambda i: abs(transitions[i]["timestampMs"] - expected))
                used_transitions.add(i)
                phase_errors.append(abs(transitions[i]["timestampMs"] - expected))
        anchors = [(rep["phases"][name], value) for name, value in ANCHORS[rep["exerciseId"]]]
        for prediction in matching:
            if prediction.get("progress") is None:
                continue
            timestamp = prediction["timestampMs"]
            for (start, a), (end, b) in zip(anchors, anchors[1:]):
                if start <= timestamp <= end:
                    expected = a + (b - a) * (timestamp - start) / (end - start)
                    progress_errors.append(abs(prediction["progress"] - expected))
                    comparable_frames += 1
                    break
    per_exercise = {}
    for exercise in sorted({r["exerciseId"] for r in reps} | {e["exerciseId"] for e in events}):
        expected = sum(r["exerciseId"] == exercise for r in reps)
        predicted = sum(e["exerciseId"] == exercise for e in events)
        matched = sum(reps[i]["exerciseId"] == exercise for i in matched_by_rep)
        per_exercise[exercise] = {"expectedReps": expected, "predictedReps": predicted, "missedReps": expected - matched,
                                  "falseCompletions": predicted - matched, "precision": matched / max(1, predicted), "recall": matched / max(1, expected)}
    model_frames = [p for p in frames if p.get("source") != "GUIDED"]
    correct = sum((p.get("exerciseId") or "other") == label_at(p["timestampMs"], annotations) for p in model_frames)
    return {"expectedReps": len(reps), "predictedReps": len(events), "countAbsoluteError": abs(len(events) - len(reps)),
            "matchedReps": len(matched_events), "missedReps": len(reps) - len(matched_events), "falseCompletions": len(events) - len(matched_events),
            "repPrecision": len(matched_events) / max(1, len(events)), "repRecall": len(matched_events) / max(1, len(reps)),
            "perExercise": per_exercise,
            "recognitionDelayMeanMs": mean(delays), "recognizedRepCoverage": len(delays) / max(1, len(reps)) if model_frames else None,
            "completionDelayP50Ms": percentile(completion_delays, .5), "completionDelayP95Ms": percentile(completion_delays, .95),
            "confirmationDelayP50Ms": percentile(confirmation_delays, .5), "confirmationDelayP95Ms": percentile(confirmation_delays, .95),
            "phaseBoundaryMaeMs": mean(phase_errors), "phaseBoundaryComparisons": len(phase_errors),
            "phaseBoundaryCoverage": len(phase_errors) / max(1, expected_phases), "missingPhaseBoundaries": expected_phases - len(phase_errors),
            "progressMae": mean(progress_errors), "progressCoverage": comparable_frames / max(1, total_rep_frames),
            "frameLabelAccuracy": correct / len(model_frames) if model_frames else None,
            "classificationFrameCoverage": len(model_frames) / max(1, len(frames)),
            "uncertainFrameRate": sum(p["status"] in ("UNCERTAIN", "WARMING_UP", "POOR_POSE") for p in frames) / max(1, len(frames)),
            "eventMatching": "event start/end within 500ms and overlapping; legacy emitted timestamp -300/+1500ms",
            "progressReference": "piecewise interpolation between human-labeled geometry phase boundaries; an approximation"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", required=True)
    parser.add_argument("--predictions", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    header, records = read_session(args.session)
    annotations = read_annotations(Path(args.session).with_suffix(".annotations.json"), header, records)
    predictions = [json.loads(line) for line in Path(args.predictions).read_text().splitlines()]
    if [p["timestampMs"] for p in predictions] != [r["timestampMs"] for r in records]:
        raise ValueError("Predictions must cover the entire session in original timestamp order")
    report = evaluate(annotations, predictions)
    Path(args.output).write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
