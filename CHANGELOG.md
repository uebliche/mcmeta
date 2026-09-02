# Changelog

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
