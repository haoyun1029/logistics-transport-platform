"""Compute paired route differences and deterministic bootstrap intervals.

Input is a JSON array (or {"records": [...]}) where each record contains the
same-OD plan and candidate values for travelTime, distance and fuel. The script
never compares different ODs. It is exploratory evidence: small samples are
reported with a limitation instead of being treated as general proof.
"""
import argparse
import json
import math
import random
from pathlib import Path

METRICS = ("travelTime", "distance", "fuel")

def load_records(path):
    data = json.loads(path.read_text(encoding="utf-8"))
    records = data if isinstance(data, list) else data.get("records", [])
    if not isinstance(records, list) or not records:
        raise ValueError("records must be a non-empty array")
    normalized = []
    for index, record in enumerate(records):
        if not isinstance(record, dict):
            raise ValueError(f"records[{index}] must be an object")
        if record.get("originCity") and record.get("destinationCity"):
            od = (record["originCity"], record["destinationCity"])
        else:
            od = (None, None)
        plan = record.get("plan", {})
        candidate = record.get("candidate", {})
        row = {"sampleId": record.get("sampleId", str(index + 1)), "originCity": od[0], "destinationCity": od[1]}
        for metric in METRICS:
            try:
                p, c = float(plan[metric]), float(candidate[metric])
            except (KeyError, TypeError, ValueError) as error:
                raise ValueError(f"records[{index}] missing numeric {metric}") from error
            if not (math.isfinite(p) and math.isfinite(c) and p > 0 and c > 0):
                raise ValueError(f"records[{index}] {metric} values must be finite and positive")
            row[metric] = c - p
            row[metric + "PercentChange"] = (c - p) / p * 100
        normalized.append(row)
    ods = {(r["originCity"], r["destinationCity"]) for r in normalized if r["originCity"] is not None}
    if len(ods) > 1:
        raise ValueError("records contain multiple ODs; run one OD at a time")
    return normalized

def bootstrap_mean(values, iterations=2000, seed=20261024):
    rng = random.Random(seed)
    means = []
    for _ in range(iterations):
        sample = [values[rng.randrange(len(values))] for _ in values]
        means.append(sum(sample) / len(sample))
    means.sort()
    lower = means[max(0, int(iterations * 0.025) - 1)]
    upper = means[min(iterations - 1, int(iterations * 0.975))]
    return {"mean": sum(values) / len(values), "lower95": lower, "upper95": upper}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--iterations", type=int, default=2000)
    args = parser.parse_args()
    if args.iterations < 100:
        raise SystemExit("iterations must be at least 100")
    records = load_records(args.input)
    summary = {}
    for metric in METRICS:
        deltas = [row[metric] for row in records]
        changes = [row[metric + "PercentChange"] for row in records]
        summary[metric] = {"delta": bootstrap_mean(deltas, args.iterations), "percentChange": bootstrap_mean(changes, args.iterations)}
    result = {
        "analysisVersion": "PAIRED_BOOTSTRAP_V1",
        "sampleCount": len(records),
        "od": {"originCity": records[0]["originCity"], "destinationCity": records[0]["destinationCity"]},
        "lowerIsBetter": True,
        "bootstrap": {"iterations": args.iterations, "seed": 20261024, "interval": "percentile 95%"},
        "records": records,
        "summary": summary,
        "limitation": "Exploratory paired analysis; small samples and simulated observations cannot prove fleet-wide superiority.",
    }
    output = args.output or args.input.with_name("paired-bootstrap.json")
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output), "sampleCount": len(records), "od": result["od"]}, ensure_ascii=False))

if __name__ == "__main__":
    main()
