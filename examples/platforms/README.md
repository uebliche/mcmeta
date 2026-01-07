# Platform Examples

This multi-project setup demonstrates using the mcmeta Gradle plugin with a
shared `:common` module and platform-specific modules:

- `:mod-fabric`
- `:mod-forge`
- `:plugin-paper`
- `:plugin-velocity`
- `:plugin-bungeecord`

Each module depends on `:common` and pulls its platform version from mcmeta.

## Run

Use Gradle properties to set the Minecraft version (and optional plugin
versions):

```sh
./gradlew build -Pmcmeta.minecraftVersion=1.21.4
```

The build uses the mcmeta plugin outputs to resolve loader versions.
