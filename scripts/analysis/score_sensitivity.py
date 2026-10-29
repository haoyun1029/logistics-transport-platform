"""Run a transparent same-OD score sensitivity analysis on an optimization snapshot.

The input is an optimization JSON artifact produced by the backend. The script
reuses the stored per-metric normalized scores; it never mixes OD values or
rebuilds scores from hidden database state. Results are exploratory evidence and
must retain the snapshot's source and sample limitations.
"""
import argparse
import json
from pathlib import Path

DEFAULT_SCENARIOS = {
    "configured_40_30_30": {"travelTime": 0.4, "distance": 0.3, "fuel": 0.3},
    "equal_33_34_33": {"travelTime": 1/3, "distance": 1/3, "fuel": 1/3},
    "time_heavy": {"travelTime": 0.6, "distance": 0.2, "fuel": 0.2},
    "distance_heavy": {"travelTime": 0.2, "distance": 0.6, "fuel": 0.2},
    "fuel_heavy": {"travelTime": 0.2, "distance": 0.2, "fuel": 0.6},
}

def read_scores(path: Path):
    data = json.loads(path.read_text(encoding="utf-8"))
    snapshot = data.get("snapshot", data)
    scores = snapshot.get("scores")
    if not isinstance(scores, list) or not scores:
        raise ValueError("optimization snapshot has no scores")
    rows = []
    for item in scores:
        route = item.get("routeId")
        if not route:
            raise ValueError("score is missing routeId")
        rows.append({"routeId": route, "travelTime": float(item["travelTimeScore"]), "distance": float(item["distanceScore"]), "fuel": float(item["fuelScore"])})
    return data, snapshot, rows

def rank(rows, weights):
    ranked = []
    for row in rows:
        composite = sum(row[k] * weights[k] for k in ("travelTime", "distance", "fuel"))
        ranked.append({"routeId": row["routeId"], "compositeScore": round(composite, 6)})
    return sorted(ranked, key=lambda x: (-x["compositeScore"], x["routeId"]))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    source, snapshot, rows = read_scores(args.snapshot)
    scenarios = []
    for name, weights in DEFAULT_SCENARIOS.items():
        scenarios.append({"name": name, "weights": weights, "ranking": rank(rows, weights)})
    winners = [item["ranking"][0]["routeId"] for item in scenarios]
    result = {
        "analysisVersion": "SCORE_SENSITIVITY_V1",
        "sourceArtifact": str(args.snapshot),
        "sourceOptimizationId": source.get("id"),
        "sourceType": snapshot.get("sourceType"),
        "validSampleCount": snapshot.get("validSampleCount"),
        "method": "Reuse stored same-OD normalized component scores; vary weights only.",
        "scenarios": scenarios,
        "winnerByScenario": winners,
        "winnerStable": len(set(winners)) == 1,
        "limitation": "Exploratory sensitivity analysis; it does not add samples or prove global optimality.",
    }
    output = args.output or args.snapshot.with_name("score-sensitivity.json")
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output), "winnerStable": result["winnerStable"], "winnerByScenario": winners}, ensure_ascii=False))

if __name__ == "__main__":
    main()
