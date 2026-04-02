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
JEXTRACT_ARTIFACTS = [
    "jextract-bindings-linux-x64",
    "jextract-bindings-macos-arm64",
    "jextract-bindings-macos-x64",
    "jextract-bindings-windows-x64",
]
HEADERS_ARTIFACT = "libghostty-vt-headers"


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
    include_dir = dist_dir / "include"
    lib_dir = dist_dir / "lib"
    bindings_dir = dist_dir / "bindings"

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
    if bindings_dir.exists():
        shutil.rmtree(bindings_dir)
    include_dir.mkdir(parents=True)
    lib_dir.mkdir(parents=True)
    bindings_dir.mkdir(parents=True)

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

    for artifact in JEXTRACT_ARTIFACTS:
        print(f"  Downloading {artifact} -> dist/bindings/{artifact}/")
        target_dir = bindings_dir / artifact
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
    print(f"  include: {sum(1 for p in include_dir.rglob('*') if p.is_file())} files")
    for f in sorted(lib_dir.iterdir()):
        if f.is_file():
            print(f"  lib: {f.name}")
    for d in sorted(bindings_dir.iterdir()):
        if d.is_dir():
            print(f"  bindings: {d.name} ({sum(1 for p in d.rglob('*') if p.is_file())} files)")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
