"""Package the tested production web build; never package local secrets."""
import json
import os
import re
import tarfile
from pathlib import Path

revision = os.environ["GITHUB_SHA"]
if not re.fullmatch(r"[0-9a-f]{40}", revision):
    raise ValueError("A full tested commit SHA is required")
dist = Path("apps/web/dist")
if not (dist / "index.html").is_file():
    raise ValueError("Missing web build")
(dist / "version.json").write_text(json.dumps({"revision": revision}))
with tarfile.open("web-deploy.tar.gz", "w:gz") as bundle:
    for path in sorted(dist.rglob("*")):
        if path.is_symlink():
            raise ValueError("No symlinks in deployment bundle")
        if path.is_file():
            bundle.add(path, arcname="dist/" + str(path.relative_to(dist)), recursive=False)
print("Packaged web build for", revision)
