#!/usr/bin/env python3
"""Download libghostty-vt artifacts from GitHub Actions workflow."""

import subprocess
import shutil
import time
import re
import json
from pathlib import Path

REPO = "Vlaaad/ghosttyfx"
WORKFLOW_FILE = "build-lib.yml"
ARTIFACTS = [
    "libghostty-vt-linux-x64",
    "libghostty-vt-macos-arm64",
    "libghostty-vt-macos-x64",
    "libghostty-vt-windows-x64",
]


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=True, text=True, **kwargs)


def main():
    libghostty_dir = Path(__file__).parent.parent / "libghostty"
    libghostty_dir.mkdir(exist_ok=True)

    print("Triggering workflow...")
    result = run(
        ["gh", "workflow", "run", WORKFLOW_FILE, "--repo", REPO], capture_output=True
    )
    output = result.stdout + result.stderr
    match = re.search(r"https://github\.com/[^/]+/[^/]+/actions/runs/(\d+)", output)
    if not match:
        raise ValueError(f"Could not find run URL in output: {output}")
    run_id = match.group(1)
    print(f"Started workflow run: {run_id}")

    print("Waiting for workflow to complete...")
    while True:
        result = run(
            [
                "gh",
                "run",
                "view",
                str(run_id),
                "--repo",
                REPO,
                "--json",
                "status,conclusion",
            ],
            capture_output=True,
        )
        output = result.stdout + result.stderr
        data = json.loads(output)
        status = data["status"]
        conclusion = data.get("conclusion")

        if status == "completed":
            if conclusion == "success":
                print("Workflow completed!")
            elif conclusion is None:
                print("Workflow completed with no conclusion")
            else:
                print(f"Workflow completed with: {conclusion}")
            break

        print(f"Status: {status}... waiting 30s")
        time.sleep(30)

    print("Downloading artifacts...")
    for artifact in ARTIFACTS:
        platform = artifact.replace("libghostty-vt-", "")
        platform_dir = libghostty_dir / platform
        print(f"  Downloading {artifact} -> {platform}/")

        run(
            [
                "gh",
                "run",
                "download",
                str(run_id),
                "--repo",
                REPO,
                "-n",
                artifact,
                "-D",
                str(platform_dir),
            ],
            capture_output=True,
        )

    print(f"\nArtifacts downloaded to {libghostty_dir}/")
    for d in sorted(libghostty_dir.iterdir()):
        if d.is_dir():
            files = list(d.rglob("*"))
            print(f"  {d.name}: {len(files)} files")
            for f in sorted(d.iterdir())[:5]:
                print(f"    - {f.name}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
