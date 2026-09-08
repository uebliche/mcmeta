# Changelog

## [2026.09.08-1bf43f0] - Remove GitHub Packages Publishing

### Removed

- Remove the GitHub Packages action, credential fields, build-script mode, Maven destination, and registry consumption instructions.

### Changed

- Use local included builds or local Maven installation for the Gradle plugin.

## [2026.09.08-8862220] - Manage Builds in Uebliche.dev

### Added

- Add managed JDK 21 actions for plugin builds, the complete example matrix, local Maven installation, and GitHub Packages publishing.
- Provide a Windows and Unix build entry point with publication credential checks and date/revision versions.

### Changed

- Route metadata reconciliation, viewer deployment, and code graph indexing through their Uebliche.dev projects and capabilities.

### Removed

- Remove GitHub Actions workflows superseded by managed builds, the docs viewer, and Uebliche.dev code graph indexing.

## [2026.08.31-bootstrap-context] - Add Shared Build Contexts

### Added

- Export a complete mcmeta build context and inherit it in later Gradle builds without refetching or rewriting metadata caches.
- Provide a minimal bootstrap build that resolves the shared context without configuring a Minecraft client project.
- Reuse one precompiled plugin build across parallel consumers instead of recompiling into shared outputs.

### Fixed

- Allow `mcmetaResolve` to apply runtime options after Gradle has already evaluated the project.

## [2026.08.27-identity-mappings] - Support Unobfuscated Minecraft Releases

### Added

- Generate identity mapping artifacts for unobfuscated Minecraft releases.
- Configure Fabric Loom with local metadata and intermediary endpoints for the
  official production namespace.

### Fixed

- Add the generated mappings dependency only when the consuming project
  exposes a mappings configuration.
