# Releasing

1. Update Core with `scripts/update-core.sh vX.Y.Z` if required, review the source diff, and run `./gradlew clean build`.
2. Set the same release version in `gradle.properties`, `Agent.VERSION` through the pinned Core, and `CHANGELOG.md`.
3. Commit and push `main`; wait for the Build workflow to pass.
4. Create and push the immutable tag `vX.Y.Z`.
5. The Release workflow rebuilds from the tag, tests, creates SHA-256, and publishes the jar and checksum to GitHub Releases.

Never move or reuse a released tag. If the workflow fails, fix the source and publish a new patch version.
