#!/usr/bin/env python3
"""Download libghostty-vt artifacts from GitHub Actions workflow."""

import json
import re
import shutil
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
HEADERS_ARTIFACT = "libghostty-vt-headers"


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
    include_dir = dist_dir / "include"
    lib_dir = dist_dir / "lib"

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

        print(f"Status: {status}... waiting 5s")
        time.sleep(5)

    if dist_dir.exists():
        shutil.rmtree(dist_dir)
    dist_dir.mkdir(parents=True)
    if include_dir.exists():
        shutil.rmtree(include_dir)
    if lib_dir.exists():
        shutil.rmtree(lib_dir)
    include_dir.mkdir(parents=True)
    lib_dir.mkdir(parents=True)

    print("Downloading artifacts...")
    print("  Downloading headers -> dist/include/")
    run(
        [
            "gh",
            "run",
            "download",
            str(run_id),
            "--repo",
            REPO,
            "-n",
            HEADERS_ARTIFACT,
            "-D",
            str(include_dir),
        ],
        capture_output=True,
    )

    for artifact in ARTIFACTS:
        print(f"  Downloading {artifact} -> dist/lib/")

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
                str(lib_dir),
            ],
            capture_output=True,
        )

    print(f"\nArtifacts downloaded to {dist_dir}/")
    print(f"  include: {sum(1 for p in include_dir.rglob('*') if p.is_file())} files")
    for f in sorted(lib_dir.iterdir()):
        if f.is_file():
            print(f"  lib: {f.name}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
