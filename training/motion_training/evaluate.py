"""Evaluate Android-exported RecognitionResult JSONL against reviewed repetition annotations."""
import argparse
import json
import statistics
from pathlib import Path
from .data import read_session, read_annotations, label_at

# Piecewise progress anchors agree with the SDK's stage allocation.
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


def evaluate(annotations, predictions):
    reps = [r for r in annotations["repetitions"] if r.get("valid", True) and r["exerciseId"] != "other"]
    events = [p for p in predictions if p.get("repCompleted")]
    matched_events = set()
    missed = 0
    delays, phase_errors, progress_errors = [], [], []
    comparable_frames = total_rep_frames = 0
    for rep in reps:
        matching = [p for p in predictions if rep["startMs"] <= p["timestampMs"] <= rep["endMs"] and p.get("exerciseId") == rep["exerciseId"]]
        total_rep_frames += sum(rep["startMs"] <= p["timestampMs"] <= rep["endMs"] for p in predictions)
        if matching:
            delays.append(matching[0]["timestampMs"] - rep["startMs"])
        possible = [i for i, event in enumerate(events) if i not in matched_events and event.get("exerciseId") == rep["exerciseId"]
                    and rep["endMs"] - 300 <= event["timestampMs"] <= rep["endMs"] + 1500]
        if possible:
            matched_events.add(min(possible, key=lambda i: abs(events[i]["timestampMs"] - rep["endMs"])))
        else:
            missed += 1
        for phase, expected in rep.get("phases", {}).items():
            found = [p["timestampMs"] for p in predictions if p.get("exerciseId") == rep["exerciseId"] and
                     rep["startMs"] - 300 <= p["timestampMs"] <= rep["endMs"] + 1500 and p.get("phase") in PHASE_ALIASES.get(phase, {phase})]
            if found:
                phase_errors.append(abs(min(found, key=lambda t: abs(t - expected)) - expected))
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
    correct = sum((p.get("exerciseId") or "other") == label_at(p["timestampMs"], annotations) for p in predictions)
    return {"expectedReps": len(reps), "predictedReps": len(events), "countAbsoluteError": abs(len(events) - len(reps)),
            "matchedReps": len(matched_events), "missedReps": missed, "falseCompletions": len(events) - len(matched_events),
            "recognitionDelayMeanMs": mean(delays), "recognizedRepCoverage": len(delays) / max(1, len(reps)),
            "phaseBoundaryMaeMs": mean(phase_errors), "phaseBoundaryComparisons": len(phase_errors),
            "progressMae": mean(progress_errors), "progressCoverage": comparable_frames / max(1, total_rep_frames),
            "frameLabelAccuracy": correct / max(1, len(predictions)),
            "uncertainFrameRate": sum(p["status"] in ("UNCERTAIN", "WARMING_UP", "POOR_POSE") for p in predictions) / max(1, len(predictions)),
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
