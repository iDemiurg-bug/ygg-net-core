# External AAR libraries

This folder is for storing AAR files that are NOT included in the repository.

## Required AAR files for build

- `yggdrasil.aar` - Yggdrasil core (must be placed here before building)

These files are too large to store in git and should be downloaded/copied manually.

## How to use

1. Place `yggdrasil.aar` in this folder
2. Run `./gradlew core:assembleRelease`

The build will NOT automatically include these AARs - they are used only for reference.
