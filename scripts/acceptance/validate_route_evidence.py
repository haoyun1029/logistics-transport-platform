"""Validate a route evidence bundle before it can be considered for approval.

This is a fail-closed intake check. It validates structure and provenance fields;
it does not decide whether a public source actually proves truck access. A record
with any missing required field remains PENDING and must be reviewed by a person.
"""
import argparse
import json
from pathlib import Path

REQUIRED_ROUTE = ("routeId", "originCity", "destinationCity", "routeStatus", "segments")
REQUIRED_SEGMENT = ("sequence", "fromCity", "toCity", "roadName", "roadType", "distanceKm", "estimatedMinutes", "geometry", "mapRequest", "mapResponse", "truckEvidence", "verifiedAt", "verifiedBy")
REQUIRED_EVIDENCE = ("sourceUrl", "sourceTitle", "checkedAt", "applicableVehicle", "conclusion")

def missing(item, fields):
    return [name for name in fields if item.get(name) in (None, "", [], {})]

def validate(data):
    errors = []
    routes = data if isinstance(data, list) else data.get("routes", [])
    if not isinstance(routes, list) or not routes:
        return ["routes must be a non-empty array"]
    seen_od = set()
    for route_index, route in enumerate(routes):
        prefix = f"routes[{route_index}]"
        errors.extend(f"{prefix}.{name} is required" for name in missing(route, REQUIRED_ROUTE))
        od = (route.get("originCity"), route.get("destinationCity"))
        if od in seen_od:
            errors.append(f"{prefix} duplicates an origin/destination pair")
        seen_od.add(od)
        if route.get("routeStatus") == "VERIFIED":
            errors.append(f"{prefix}.routeStatus must be PENDING for intake; human review controls approval")
        for segment_index, segment in enumerate(route.get("segments") or []):
            sp = f"{prefix}.segments[{segment_index}]"
            errors.extend(f"{sp}.{name} is required" for name in missing(segment, REQUIRED_SEGMENT))
            evidence = segment.get("truckEvidence")
            if isinstance(evidence, dict):
                errors.extend(f"{sp}.truckEvidence.{name} is required" for name in missing(evidence, REQUIRED_EVIDENCE))
            if segment.get("sequence") != segment_index + 1:
                errors.append(f"{sp}.sequence must be contiguous and start at 1")
            geometry = segment.get("geometry")
            if isinstance(geometry, dict) and geometry.get("coordinateSystem") not in ("WGS84", "GCJ02"):
                errors.append(f"{sp}.geometry.coordinateSystem must be WGS84 or GCJ02")
    return errors

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    args = parser.parse_args()
    data = json.loads(args.bundle.read_text(encoding="utf-8"))
    errors = validate(data)
    result = {"valid": not errors, "status": "READY_FOR_HUMAN_REVIEW" if not errors else "PENDING", "errorCount": len(errors), "errors": errors}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if not errors else 1

if __name__ == "__main__":
    raise SystemExit(main())


