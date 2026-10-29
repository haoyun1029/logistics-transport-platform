"""Build a redacted, hash-based manifest for an acceptance evidence directory.

The manifest records file names, sizes and SHA-256 values plus non-secret runtime
metadata. It never reads or emits .env files, credentials, tokens or private keys.
"""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import platform
import sys

SECRET_NAMES = {".env", ".env.local", ".env.production"}
SECRET_SUFFIXES = (".pem", ".key", ".p12", ".pfx")

def excluded(path: Path) -> bool:
    name = path.name.lower()
    return name in SECRET_NAMES or name == "evidence-manifest.json" or name.endswith(SECRET_SUFFIXES)

def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()

def build(root: Path, label: str) -> dict:
    if not root.is_dir():
        raise SystemExit("evidence directory does not exist: " + str(root))
    files = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or excluded(path):
            continue
        files.append({"path": path.relative_to(root).as_posix(), "bytes": path.stat().st_size, "sha256": sha256(path)})
    return {
        "manifestVersion": "EVIDENCE_MANIFEST_V1",
        "scope": "REPRODUCIBILITY_EVIDENCE_BUNDLE",
        "label": label,
        "createdAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "runtime": {"python": platform.python_version(), "implementation": platform.python_implementation(), "platform": platform.platform(), "executable": Path(sys.executable).name},
        "fileCount": len(files),
        "files": files,
        "excludedSecretPatterns": [".env", ".env.*", "*.pem", "*.key", "*.p12", "*.pfx"],
    }

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_dir", type=Path, help="directory containing retained acceptance evidence")
    parser.add_argument("--label", default=None, help="human-readable run label")
    parser.add_argument("--output", type=Path, default=None, help="manifest path; defaults to run_dir/evidence-manifest.json")
    args = parser.parse_args()
    output = args.output or args.run_dir / "evidence-manifest.json"
    manifest = build(args.run_dir.resolve(), args.label or args.run_dir.name)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output.resolve()), "fileCount": manifest["fileCount"], "manifestVersion": manifest["manifestVersion"]}, ensure_ascii=False))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())

