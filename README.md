# NPShips

NPShips provides a schematic editor and configurable spawning, tracking and removal of physical structures powered by Sable. Schematics can include block and entity settings, loot, segment variants and supported mod connections. Content packs can be exported as addon mods.

## Installation

Use Minecraft **1.21.1**, **NeoForge 21.1.238 or newer**, and Java **21**. Place the NPShips mod JAR in the client and server `mods` directories.

Required mods: **Create 6.0.10+**, **Ponder 1.0.82+**, **Flywheel 1.0.6+**, **Sable 2.0.6+**, and **Sable Companion 1.6.0+**. Install the dependencies required by those mods too. Use their Minecraft 1.21.1 / NeoForge releases.

**Create: Aeronautics / Simulated 1.3.2+** is optional and recommended for its ship mechanisms. NPShips also includes integrations for CC: Tweaked, Create: Big Cannons and Create: Radars. Install these mods and their dependencies when a content pack uses them; they are not required for the base NPShips installation.

Report problems at [GitHub Issues](https://github.com/Eynny/NPShips/issues). Include the NPShips version, mod list, log and reproduction steps.

## Build

Run `./gradlew build :npships-api:build` with Java 21 (`gradlew.bat` on Windows). Gradle downloads and verifies the required build inputs; no local mod JAR directory is required. Outputs are under `build/libs` and `npships-api/build/libs`.

## API

In a Minecraft 1.21.1 / NeoForge ModDev project:

```groovy
repositories {
    maven {
        url = 'https://eynny.github.io/NPShips/maven/'
        content { includeGroup 'io.github.eynny' }
    }
}
dependencies {
    compileOnly 'io.github.eynny:npships-api:0.0.86'
}
```

The API is a compile-time dependency. Install the normal NPShips mod at runtime; do not embed the API JAR in your mod. Declare NPShips as a runtime mod dependency if your mod requires it. Call server APIs on the server thread. `NPShipsSpawnApi.requestExact` accepts a fixed origin and returns an admission result; an accepted `SpawnJob` reports completion separately.

## License

Original NPShips code and owned assets are MIT licensed. See [LICENSE](LICENSE), [third-party notices](NPShipsThirdPartyNotices.md), and the retained third-party and MDK license texts.
