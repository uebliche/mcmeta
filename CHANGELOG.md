# Changelog

## [2026.08.27-identity-mappings] - Support Unobfuscated Minecraft Releases

### Added

- Generate identity mapping artifacts for unobfuscated Minecraft releases.
- Configure Fabric Loom with local metadata and intermediary endpoints for the
  official production namespace.

### Fixed

- Add the generated mappings dependency only when the consuming project
  exposes a mappings configuration.
