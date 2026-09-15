"""Train on reviewed coordinate sessions and export a versioned, checksum-verified bundle."""
import argparse
import hashlib
import json
import os
import time
import zipfile
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")


def classification_report(y, scores, classes):
    import numpy as np
    predicted = scores.argmax(axis=1)
    matrix = np.zeros((len(classes), len(classes)), dtype=int)
    for truth, prediction in zip(y, predicted):
        matrix[truth, prediction] += 1
    per_class = {}
    for i, label in enumerate(classes):
        tp = int(matrix[i, i])
        precision = tp / max(1, int(matrix[:, i].sum()))
        recall = tp / max(1, int(matrix[i].sum()))
        per_class[label] = {"precision": precision, "recall": recall,
                            "f1": 2 * precision * recall / max(1e-9, precision + recall), "support": int(matrix[i].sum())}
    return {"classes": classes, "confusionMatrix": matrix.tolist(), "perClass": per_class,
            "accuracy": float(np.mean(predicted == y)), "macroF1": sum(c["f1"] for c in per_class.values()) / len(classes)}


def tune_thresholds(y, scores, other_index):
    """Select abstention thresholds using validation only; report precision AND coverage."""
    import numpy as np
    ranked = np.sort(scores, axis=1)
    predicted = scores.argmax(axis=1)
    best = None
    for confidence in (.4, .5, .6, .7, .8, .9):
        for margin in (.05, .1, .15, .2, .3):
            accepted = (ranked[:, -1] >= confidence) & (ranked[:, -1] - ranked[:, -2] >= margin)
            selected_motion = accepted & (predicted != other_index)
            tp = int(((predicted == y) & selected_motion).sum())
            fp = int(((predicted != y) & selected_motion).sum())
            fn = int(((y != other_index) & ~((predicted == y) & selected_motion)).sum())
            f1 = 2 * tp / max(1, 2 * tp + fp + fn)
            candidate = (f1, float(accepted.mean()), confidence, margin)
            if best is None or candidate > best:
                best = candidate
    return {"confidenceThreshold": best[2], "marginThreshold": best[3],
            "validationSelectiveMotionF1": best[0], "validationCoverage": best[1]}


def build_model(tf):
    inputs = tf.keras.Input(shape=(180, 33), name="features")
    x = inputs
    for dilation in (1, 2, 4, 8, 16, 32):
        x = tf.keras.layers.Conv1D(32, 3, padding="causal", dilation_rate=dilation, activation="relu")(x)
    x = tf.keras.layers.Cropping1D((179, 0))(x)
    x = tf.keras.layers.Flatten()(x)
    outputs = tf.keras.layers.Dense(5, activation="softmax", name="scores")(x)
    model = tf.keras.Model(inputs, outputs)
    model.compile(optimizer=tf.keras.optimizers.Adam(1e-3), loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    return model


def train(args):
    import numpy as np
    import tensorflow as tf
    from .data import load_dataset
    from .features import CLASSES

    tf.keras.utils.set_random_seed(args.seed)
    tf.config.threading.set_inter_op_parallelism_threads(2)
    tf.config.threading.set_intra_op_parallelism_threads(2)
    dataset, mean, std, split = load_dataset(args.data, args.seed)
    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=True)
    model = build_model(tf)
    x, y = dataset["train"]["x"], dataset["train"]["y"]
    rng = np.random.default_rng(args.seed)
    history = []
    best_loss, best_weights = float("inf"), None
    for epoch in range(args.epochs):
        model.reset_metrics()
        order = rng.permutation(len(x))
        for offset in range(0, len(order), args.batch_size):
            indices = order[offset:offset + args.batch_size]
            metrics = model.train_on_batch(x[indices], y[indices], return_dict=True)
        val_scores = model(dataset["validation"]["x"], training=False).numpy()
        val_loss = float(-np.log(np.maximum(val_scores[np.arange(len(val_scores)), dataset["validation"]["y"]], 1e-9)).mean())
        history.append({"epoch": epoch + 1, "loss": float(metrics["loss"]), "validationLoss": val_loss})
        print(json.dumps(history[-1]), flush=True)
        if val_loss < best_loss:
            best_loss, best_weights = val_loss, model.get_weights()
    model.set_weights(best_weights)
    val_scores = model(dataset["validation"]["x"], training=False).numpy()
    test_scores = model(dataset["test"]["x"], training=False).numpy()
    thresholds = tune_thresholds(dataset["validation"]["y"], val_scores, CLASSES.index("other"))

    class Serving(tf.Module):
        def __init__(self, network):
            super().__init__()
            self.network = network

        @tf.function(input_signature=[tf.TensorSpec([1, 180, 33], tf.float32, name="features")])
        def infer(self, features):
            return {"scores": self.network(features, training=False)}

    serving = Serving(model)
    # Freeze trained variables explicitly. The Android artifact has no variable-init dependency.
    from tensorflow.python.framework.convert_to_constants import convert_variables_to_constants_v2
    frozen = convert_variables_to_constants_v2(serving.infer.get_concrete_function())
    converter = tf.lite.TFLiteConverter.from_concrete_functions([frozen])
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    converted = converter.convert()
    (out / "motion.tflite").write_bytes(converted)
    interpreter = tf.lite.Interpreter(model_content=converted, num_threads=2)
    interpreter.allocate_tensors()
    input_spec, output_spec = interpreter.get_input_details()[0], interpreter.get_output_details()[0]
    if input_spec["shape"].tolist() != [1, 180, 33] or output_spec["shape"].tolist() != [1, 5]:
        raise ValueError("Unexpected exported tensor shape")
    errors, latency = [], []
    for index, window in enumerate(dataset["test"]["x"]):
        interpreter.set_tensor(input_spec["index"], window[None])
        start = time.perf_counter()
        interpreter.invoke()
        latency.append((time.perf_counter() - start) * 1000)
        actual = interpreter.get_tensor(output_spec["index"])[0]
        errors.append(float(np.max(np.abs(actual - test_scores[index]))))
    if max(errors) > 1e-4:
        worst = int(np.argmax(errors))
        sample = dataset["test"]["x"][worst:worst+1]
        interpreter.set_tensor(input_spec["index"], sample)
        interpreter.invoke()
        raise ValueError(f"Conversion parity failed: {max(errors)}; batch={test_scores[worst].tolist()}, "
                         f"single={model(sample, training=False).numpy()[0].tolist()}, lite={interpreter.get_tensor(output_spec['index'])[0].tolist()}")

    manifest = {
        "schemaVersion": 1, "featureVersion": 1, "modelFile": "motion.tflite",
        "sha256": hashlib.sha256(converted).hexdigest(), "classes": CLASSES,
        "sampleRateHz": 30, "windowSize": 180, "featureCount": 32,
        "mean": mean.tolist(), "std": std.tolist(),
        "confidenceThreshold": thresholds["confidenceThreshold"], "marginThreshold": thresholds["marginThreshold"],
        "stableDurationMs": 200, "minValidSteps": 15,
        "trainingData": "synthetic" if args.synthetic else "real",
        "progress": {"straightAngle": 160.0, "bentKneeAngle": 105.0, "bentElbowAngle": 100.0,
                     "uprightTilt": 45.0, "plankTilt": 65.0, "jumpHeightInTorso": .12,
                     "stablePoseMs": 150, "minimumRepMs": 600, "maximumRepMs": 15000, "completionDelayMs": 500},
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    report = {"trainingData": manifest["trainingData"], "seed": args.seed, "participantSplit": split,
              "windowCounts": {name: len(partition["y"]) for name, partition in dataset.items()},
              "validation": classification_report(dataset["validation"]["y"], val_scores, CLASSES),
              "test": classification_report(dataset["test"]["y"], test_scores, CLASSES),
              "thresholdSelection": thresholds, "history": history,
              "conversionMaxAbsError": max(errors),
              "hostInferenceMs": {"median": float(np.median(latency)), "p95": float(np.percentile(latency, 95))},
              "deviceInferenceMs": None, "sequenceMetrics": None,
              "note": "Host timings are not Android timings. Evaluate exported SDK replay results for repetition/phase/progress/recognition latency."}
    (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    # A shared vector lets Android instrumentation verify the actual native runtime output.
    vector = {"input": dataset["test"]["x"][0].reshape(-1).tolist(), "scores": test_scores[0].tolist()}
    (out / "parity.json").write_text(json.dumps(vector) + "\n")
    with zipfile.ZipFile(out / "motion-model.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for name in ("manifest.json", "motion.tflite"):
            archive.write(out / name, name)
    print(f"Bundle: {out / 'motion-model.zip'}", flush=True)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--synthetic", action="store_true", help="Mark the bundle as synthetic smoke-test data, never production")
    args = parser.parse_args()
    if args.epochs < 1 or args.batch_size < 1:
        parser.error("epochs and batch-size must be positive")
    train(args)


if __name__ == "__main__":
    main()
