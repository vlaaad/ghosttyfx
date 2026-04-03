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
    "libghostty-vt-linux-x86_64",
    "libghostty-vt-macos-aarch64",
    "libghostty-vt-macos-x86_64",
    "libghostty-vt-windows-x86_64",
]


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=True, text=True, **kwargs)


def ensure_synced_with_main(repo_dir: Path) -> None:
    run(["git", "fetch", "origin", "main", "--quiet"], cwd=repo_dir)

    status = run(
        ["git", "status", "--porcelain=v2", "--branch"],
        cwd=repo_dir,
        capture_output=True,
    ).stdout.splitlines()

    dirty_entries = [line for line in status if line and not line.startswith("#")]

    if dirty_entries:
        raise RuntimeError(
            "Refusing to trigger CI because the working tree is not clean. "
            "Commit, stash, or remove local changes so the checkout matches origin/main."
        )

    head = run(
        ["git", "rev-parse", "HEAD"],
        cwd=repo_dir,
        capture_output=True,
    ).stdout.strip()
    origin_main = run(
        ["git", "rev-parse", "origin/main"],
        cwd=repo_dir,
        capture_output=True,
    ).stdout.strip()

    if head != origin_main:
        raise RuntimeError(
            "Refusing to trigger CI because HEAD does not match origin/main. "
            "Push or reset your local branch so the workflow runs against the same commit."
        )


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
    repo_dir = Path(__file__).parent.parent
    ensure_synced_with_main(repo_dir)

    dist_dir = repo_dir / "dist"
    platforms_dir = dist_dir / "platforms"

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
    if platforms_dir.exists():
        shutil.rmtree(platforms_dir)
    platforms_dir.mkdir(parents=True)

    print("Downloading artifacts...")
    for artifact in ARTIFACTS:
        target_dir = platforms_dir / artifact
        print(f"  Downloading {artifact} -> dist/platforms/{artifact}/")
        target_dir.mkdir(parents=True, exist_ok=True)
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
                str(target_dir),
            ],
            capture_output=True,
        )

    print(f"\nArtifacts downloaded to {dist_dir}/")
    for d in sorted(platforms_dir.iterdir()):
        if d.is_dir():
            print(f"  platform: {d.name} ({sum(1 for p in d.rglob('*') if p.is_file())} files)")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
