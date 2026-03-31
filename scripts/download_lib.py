#!/usr/bin/env python3
"""Download libghostty-vt artifacts from GitHub Actions workflow."""

import subprocess
import shutil
import time
from pathlib import Path

REPO = "ghostty-org/ghostty"
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
        ["gh", "workflow", "run", WORKFLOW_FILE, "--repo", REPO, "--json", "runId"]
    )
    import json

    run_data = json.loads(result.stdout)
    run_id = run_data["runId"]
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
            ]
        )
        data = json.loads(result.stdout)
        status = data["status"]
        conclusion = data.get("conclusion")

        if status == "completed":
            if conclusion == "success":
                print("Workflow completed successfully!")
            else:
                print(f"Workflow failed: {conclusion}")
                return 1
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
                "--force",
            ]
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
