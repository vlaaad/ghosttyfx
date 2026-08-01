---
name: update-ghostty
description: Review upstream Ghostty commits and provide a concise, GhosttyFX-relevant update recommendation.
---
# Update Ghostty

## Phase 1: Review

Investigate read-only, except for fetching upstream refs. Keep the worktree, branch, index, and submodule checkout unchanged.

1. Fetch the upstream remote of the Ghostty submodule.
2. Compare the current commit with HEAD of the default upstream branch.
3. Examine each commit for direct or indirect effects on libghostty and GhosttyFX.
4. Use related pull requests to understand the changes.
5. Focus on changes that affect libghostty or GhosttyFX.
6. Check the C API, ABI, shared terminal behavior, rendering, input, platform support, and native build requirements.
7. Report concisely, preferably within 200 words:
   - Start with current and upstream commits and a linked range with the commit count.
   - List only changes with a concrete effect on libghostty or GhosttyFX under `Relevant changes`.
   - Do not list upstream-only work merely because it was reviewed or touched the full Ghostty application API.
   - Add `Compatibility`, `New features`, or `Risks` sections only when they contain material findings. Omit no-op sections instead of saying there are none.
   - For new features, include only features GhosttyFX can implement through APIs available in the reviewed upstream commit, and state their user value.
   - End with a direct update recommendation and its primary reason.

If upstream is ahead, ask, "Do you want me to update Ghostty?" Then stop. If there is nothing to update, state that Ghostty is up to date and stop without asking.

## Phase 2: Update

Begin after the user answers yes. Approval authorizes the actions below.

1. Use a clean worktree and an unused `update-ghostty` branch name.
2. Fast-forward local `main`. Create `update-ghostty`.
3. Set the submodule to the upstream commit from phase 1.
4. Limit code changes to required compatibility work.
5. Run native builds and tests only in GitHub Actions.
6. Commit the changes. Push `update-ghostty`.
7. Wait for all required CI jobs on the exact commit. Fix failures minimally and repeat.
8. Download and validate all artifacts from that successful CI run:
   ```sh
   mvn -N -Pdownload-cross-platform-artifacts exec:exec@download-cross-platform-artifacts
   ```
9. Verify the artifacts in `dist/<ghostty-commit>/`.
10. Report the branch, commits, CI links, artifact path, and new features with their user value.

End before merge or pull-request creation. Keep `main` unchanged after branch creation.

## Phase 3: Follow-up work

Run Java/Maven tests locally as needed. Reserve commits and pushes for the user.

## Phase 4: Integration

Begin after the user says, "ready to integrate." Approval authorizes the actions below.

1. Fast-forward local `main` to the update branch. Push `main`.
2. Delete the merged update branch locally and remotely.
3. Delete obsolete local and remote `update-ghostty` branches.
4. Leave `main` checked out, synchronized with `origin`, and clean.
