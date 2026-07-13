# Development and Release Policy

This repository uses separate flows for daily development and public releases.

## Branches

- `main`: stable code only. Do not use ordinary development commits here.
- `develop`: daily development branch. Push normal work here.

## Releases

The Android release workflow must not run for ordinary pushes.

Public releases are created only by:

- pushing a version tag matching `v*`, for example `v1.2.3`; or
- manually running the `Android Release` workflow from GitHub Actions.

The app update checker should continue to use GitHub Releases as the public update source. Development builds should not be published as GitHub Releases.

## Recommended Flow

1. Develop and test on `develop`.
2. Merge `develop` into `main` only after the version is considered stable.
3. Create and push a `v*` tag from the stable commit when the release should become visible to users.
