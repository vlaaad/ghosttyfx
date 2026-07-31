---
name: update-ghostty
description: Review upstream Ghostty commits and recommend whether to update.
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
7. Report:
   - Current and upstream commits, range, count, and links
   - Relevant changes and effects on GhosttyFX
   - Required compatibility changes
   - New features that GhosttyFX can implement and their user value
   - Risks, unknown conditions, and an update recommendation

Ask, "Do you want me to update Ghostty?" Then stop.

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
