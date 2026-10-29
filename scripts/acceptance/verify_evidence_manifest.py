"""Verify an evidence-manifest.json against its evidence directory.

Exit code 0 means every manifest file exists with the recorded size and SHA-256,
and no additional non-secret files are present. Secrets are never opened.
"""
import argparse
import hashlib
import json
from pathlib import Path

SECRET_NAMES = {".env", ".env.local", ".env.production"}
SECRET_SUFFIXES = (".pem", ".key", ".p12", ".pfx")

def excluded(path: Path) -> bool:
    name = path.name.lower()
    return name in SECRET_NAMES or name == "evidence-manifest.json" or name.endswith(SECRET_SUFFIXES)

def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_dir", type=Path)
    parser.add_argument("manifest", type=Path, nargs="?", help="manifest path; defaults to run_dir/evidence-manifest.json")
    args = parser.parse_args()
    root = args.run_dir.resolve()
    manifest_path = (args.manifest or root / "evidence-manifest.json").resolve()
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    expected = {item["path"]: item for item in data.get("files", [])}
    actual = {path.relative_to(root).as_posix(): path for path in root.rglob("*") if path.is_file() and not excluded(path)}
    missing, changed, extra = [], [], []
    for name, item in expected.items():
        path = actual.get(name)
        if path is None:
            missing.append(name)
            continue
        if path.stat().st_size != item.get("bytes") or digest(path) != item.get("sha256"):
            changed.append(name)
    extra = sorted(set(actual) - set(expected) - {manifest_path.relative_to(root).as_posix()})
    result = {"manifestVersion": data.get("manifestVersion"), "expected": len(expected), "missing": sorted(missing), "changed": sorted(changed), "extra": extra, "valid": not missing and not changed and not extra}
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result["valid"] else 1

if __name__ == "__main__":
    raise SystemExit(main())

