#!/usr/bin/env python3
"""Download libghostty-vt artifacts from GitHub Actions workflow."""

import json
import re
import subprocess
import time
from pathlib import Path

REPO = "vlaaad/ghosttyfx"
WORKFLOW_FILE = "build-lib.yml"
ARTIFACTS = [
    "libghostty-vt-linux-x64",
    "libghostty-vt-macos-arm64",
    "libghostty-vt-macos-x64",
    "libghostty-vt-windows-x64",
]


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=True, text=True, **kwargs)


def workflow_status(run_id: str) -> dict:
    result = run(
        [
            "gh",
            "run",
            "view",
            str(run_id),
            "--repo",
            REPO,
            "--json",
            "status,conclusion,jobs,url",
        ],
        capture_output=True,
    )
    return json.loads(result.stdout)


def main():
    dist_dir = Path(__file__).parent.parent / "dist"
    dist_dir.mkdir(exist_ok=True)

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

    print("Waiting for workflow to complete or the first job failure...")
    while True:
        data = workflow_status(run_id)
        status = data["status"]
        conclusion = data.get("conclusion")
        jobs = data.get("jobs", [])

        failed_jobs = [
            job["name"]
            for job in jobs
            if job.get("conclusion") in {"failure", "cancelled", "timed_out", "startup_failure"}
        ]

        if failed_jobs:
            print(f"Workflow failed early: {', '.join(failed_jobs)}")
            print(f"Run URL: {data['url']}")
            return 1

        if status == "completed":
            if conclusion != "success":
                print(f"Workflow completed with: {conclusion}")
                print(f"Run URL: {data['url']}")
                return 1
            print("Workflow completed successfully!")
            break

        print(f"Status: {status}... waiting 1s")
        time.sleep(1)

    print("Downloading artifacts...")
    for artifact in ARTIFACTS:
        platform = artifact.replace("libghostty-vt-", "")
        platform_dir = dist_dir / platform
        print(f"  Downloading {artifact} -> dist/{platform}/")

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

    print(f"\nArtifacts downloaded to {dist_dir}/")
    for d in sorted(dist_dir.iterdir()):
        if d.is_dir():
            files = list(d.rglob("*"))
            print(f"  {d.name}: {len(files)} files")
            for f in sorted(d.iterdir())[:5]:
                print(f"    - {f.name}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
