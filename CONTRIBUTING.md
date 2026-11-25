# Contributing

Open an issue before a large change. Keep platform-specific code in `src/`; shared behavior belongs in the Core repository.
Do not edit vendored `core/` directly: update it with `scripts/update-core.sh <tag>` and commit the resulting source and
`core.lock` together. Run `./gradlew clean build` before submitting a pull request. Do not add credentials, production data,
player identifiers or generated build output. By contributing, you agree that your contribution is licensed under MIT.
