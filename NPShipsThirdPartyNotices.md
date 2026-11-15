# NPShips licensing and third-party notices

2026-11-05. Original NPShips code and original Eynny-owned resources, including
original models, textures and icons, are licensed under the root [MIT LICENSE](LICENSE),
Copyright (c) 2026 Eynny. This scope follows the owner's explicit decision.
It does not relicense third-party contributions, imported content, Minecraft,
NeoForge, generated runtime/world files or dependency JARs. Ambiguous origins stay
unresolved. This is a technical provenance record, not clearance to redistribute
all dependencies or the private working tree.

## Included files and independent notices

| Files/material | Origin and applicable notice | Distribution boundary |
|---|---|---|
| Original `src/main/java/com/eynny/npships/**` and original project scripts | Eynny; root MIT, except any independently identified upstream portions | Development and Codex source reviews used AI assistance. The user's requirements/physical review does not certify human review of each source line. There is no reliable line-by-line AI authorship map or claim that every contribution was independently invented |
| Original `src/main/resources/assets/npships/**` | Owner-approved MIT for original owned artwork/models/icons | File-level inventory retains unresolved derived/imported exceptions. Owner-supplied artwork is integrated; old missing-icon/desk TODOs are historical |
| `build.gradle`, `settings.gradle`, original wrapper launch scripts and `src/main/templates/META-INF/neoforge.mods.toml` template portions | NeoForged MDK; retain root `TEMPLATE_LICENSE.txt`, MIT 2023 NeoForged project | NPShips additions do not erase the template notice |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` | Gradle upstream; Apache-2.0, `licenses/Gradle-Apache-2.0.txt` | Launcher headers retained. Exact wrapper SHA matches official Gradle 8.14.3; it launches the separately pinned Gradle 9.2.1 distribution |
| `src/main/java/com/eynny/npships/mixin/NPShipsPonderWorkerTooltipMixin.java` | NPShips temporary compatibility guard targeting Ponder's original localized formatting contract | Ponder MIT 2022 The Create Team retained in `licenses/Ponder-MIT.txt` for reproduced formatting/API expressions; this is no general FontManager fix. The guard avoids calling a not-yet-initialized localization owner from Ponder worker threads |
| `src/main/java/com/eynny/npships/integration/create/CreateAssembly.java` connection predicates | Native Create `BlockMovementChecks`/stickiness semantics; the small expression correspondence is disclosed conservatively as an adapted portion | Retain `licenses/Create-MIT.txt`, Copyright (c) The Create Team / The Creators of Create. The surrounding bounded membership/marker adapter is NPShips code. No complete upstream traversal, Create asset, Sable implementation or dependency binary is included. Assembly starts from NPShips coordinate markers using dependency APIs. |
| `src/main/resources/npships/licenses/MIT.txt` | Standard SPDX MIT template | Addon export replaces holder/year only after author choice. It is independent of the mod's Eynny LICENSE and never automatically licenses third-party addon content |
| Private authored schematics, programs, worlds, local dependencies and development evidence | Mixed private/generated/third-party content; individual rights may be unresolved | Excluded from this public source tree and NPShips release artifacts; no MIT assertion |

Mod/API binary and sources JARs, and the API Javadoc JAR, include this notice, original MIT, MDK notice and the
`licenses/` notices under `META-INF/`. They do **not** shade or bundle the mods below.
Native registry IDs, ItemStack fallbacks, API imports/reflection and Mixin injection
targets do not themselves embed upstream implementation or texture bytes.
Hash/marker review cannot exclude modified copying; newly discovered substantial
borrowed portions require file-specific notices and rights review before release.

## Exact development/runtime dependencies

Hashes of the reviewed Maven JARs and generated Simulated input are maintained in
[`gradle/dependency-integrity.json`](gradle/dependency-integrity.json).
Independent nested component notices apply to the dependencies that contain them.
Metadata declarations and moving upstream branch licenses are distinguished from
exact installed notice bytes below.

| Artifact/version | Actual terms/evidence | NPShips use and unresolved limits |
|---|---|---|
| Minecraft 1.21.1 | Mojang proprietary game; [Minecraft usage guidelines](https://www.minecraft.net/en-us/usage-guidelines) | Downloaded/decompiled development inputs are not NPShips MIT source and must not be included in the source snapshot |
| NeoForge 21.1.238; ModDevGradle 2.0.143; Parchment 2024.11.17 | [NeoForge](https://github.com/neoforged/NeoForge/blob/1.21.x/LICENSE.txt) and [ModDevGradle](https://github.com/neoforged/ModDevGradle/blob/main/LICENSE) upstream LGPL-2.1 texts; Parchment/mappings have independent terms | Resolved build inputs, not embedded NPShips implementation. Current branch notices are corroboration, not an exact-version legal certificate; no blanket MIT claim for generated game/mapping output or transitive closure |
| Create 6.0.10-280 slim | Installed metadata refers to LICENSE; slim JAR lacks full notice. [Official scope](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/LICENSE.md): code MIT, assets All Rights Reserved | Native API/model use, no shaded Create. Current branch corroborates scope but does not prove exact historical source revision of every asset |
| Ponder 1.0.82+mc1.21.1 | Exact JAR `LICENSE_Ponder`: MIT, Copyright (c) 2022 The Create Team | Independent notice retained. Temporary worker guard removal requires verified upstream lifecycle correction and full reload/search tests |
| Flywheel API/runtime 1.0.6 | Exact `META-INF/LICENSE.md`: MIT, Copyright (c) 2021-2024 Jozufozu | API/runtime dependency only; neither its code nor graphics become NPShips artwork |
| Registrate MC1.21-1.3.0+67 | No license text in exact JAR; [upstream 1.21/dev LICENSE](https://github.com/tterrag1098/Registrate/blob/1.21/dev/LICENSE) is MPL-2.0 | Build/runtime API dependency, not shaded; file-level source/notice obligations apply if copied. Exact binary/source correspondence remains required before repackaging |
| Aeronautics bundled 1.3.2; nested Aeronautics/Offroad/Simulated 1.3.2 | Exact `LICENSE.md`: MIT code, All Rights Reserved assets. [Official source](https://github.com/Creators-of-Aeronautics/Simulated-Project/blob/main/LICENSE.md) | Official Modrinth artifact, exact SHA pinned; personal provisioning is distinct from republishing the full asset-bearing bundle. No broad redistribution clearance asserted |
| Simulated 1.3.2 reviewed nested compile input | Exact bytes from the pinned Aeronautics bundle entry `META-INF/jarjar/dev.simulated_team.simulated.simulated-neoforge-1.21.1-1.3.2.jar`; same split license | Gradle automatically extracts this exact entry into build-owned generated inputs and verifies both outer and inner SHA-256. The same-version public standalone release changes code/platform requirements and is not substituted. NPShips does not bundle or republish this JAR. |
| Sable 2.0.6 | Exact JAR `LICENSE.md`: [PolyForm Shield 1.0.0](https://polyformproject.org/licenses/shield/1.0.0) | Not MIT. Permitted-purpose, competition, notice and other conditions require assessment for the actual distribution/use; no bypass or internal patch |
| Sable Companion 1.6.0 official Maven compile input (35,444 bytes) | Exact `LICENSE`: MIT, Copyright (c) 2026 RyanHCode; [official source](https://github.com/ryanhcode/sable-companion) | Pinned official Maven coordinate `dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:1.6.0`, SHA-256 `873633e35046e3761b277ff8a1ecad0d55d9a3014fa81a0b084c9aecba1f3bed`. All 14 classes match the retired 35,448-byte local reference. Metadata differences satisfy the current loader/platform. Sable supplies a byte-identical nested copy at runtime; NPShips neither embeds nor ships Companion. |
| CC:Tweaked 1.120.2 | Exact metadata CCPL; [exact-tag REUSE](https://github.com/cc-tweaked/CC-Tweaked/blob/v1.21.1-1.120.2/REUSE.toml), local `LICENSES/LicenseRef-CCPL.txt`, MPL-2.0/CC0 and file-specific terms | Optional integration. Do not label whole project MIT/MPL; preserve file notices if any portions are redistributed |
| CBC 5.11.7 (`bOiDu0LS`) | Metadata MIT and CC BY-NC-SA; [upstream scope](https://github.com/Cannoneers-of-Create/CreateBigCannons/blob/create-v6-1.21.1/LICENSE.md): source/localization MIT, handmade models/textures CC BY-NC-SA 4.0 | Optional runtime/native API use. Exact JAR lacks full text; source revision and applicable attribution must be established before copying or republishing its portions |
| RPL 2.1.2 (`hZ6B2Z0x`) | Metadata MIT; [upstream MIT notice](https://github.com/Wagers-of-Industrial-Warfare/RitchiesProjectileLib/blob/master/LICENSE), Copyright (c) 2023 Wagers-of-Industrial-Warfare | Exact binary lacks full text; branch corroboration is not exact-version redistribution clearance |
| Radars 0.4.9.4 (`AntNFNAx`) | Exact metadata custom; [current source-available terms](https://github.com/Arsenalists-of-Create/Create-Radar/blob/master/LICENSE) distinguish optional support from hard dependencies | NPShips optional detection/reflection; not bundled. Local 0.4.4 source is not exact 0.4.9.4. Confirm exact-version terms before publication; no blanket MIT/redistribution claim |
| Veil 4.3.2 nested in Sable | Exact `LICENSE_Veil`: LGPLv3; [upstream](https://github.com/FoundryMC/Veil/blob/1.21/LICENSE) | Independent LGPL compliance/source/relinking obligations if redistributed; NPShips does not bundle/patch it |
| Sable Rapier bridge installed with Sable2.0.6 and native Rapier | Exact bridge PolyForm Shield; `natives/sable_rapier/LICENSE-RAPIER`: Apache-2.0 | Native binary/source and notice obligations independent of NPShips MIT |
| Veil nested GLSL processor 0.2.3 and Molang compiler 3.1.1.19 | No license text in those exact nested JARs | Rights/source-correspondence unresolved; no clearance to repackage closure |
| CC nested Cobalt 0.9.9 and jzlib 1.1.3 | No license text in those exact nested JARs; exact CC REUSE/source distribution is additional evidence | Need component-specific source/notices before binary repackaging; not shipped in NPShips artifacts |
| Radars nested Companion 1.5.0; Sable nested Companion 1.6.0 | Independent embedded MIT notices, RyanHCode | Preserve each notice in its upstream distribution. Version 1.5.0 is not interchangeable with 1.6.0. The reviewed Sable nested 1.6.0 is byte-identical to the official compile-only Maven input. |

No exact binary/source rights are inferred solely from availability on Maven,
Modrinth or GitHub. Original-source MIT work is complete separately from the still
pending legal/publication decisions for ambiguous content and third-party closure.


## Final R7 distribution boundary ? 2026-11-05

The exact current compile/runtime closure and nested notices were inspected again:
129 resolved JAR paths, including platform/build inputs. This count is not a claim
that129libraries are shipped. NPShips normal/API binaries contain their own
implementation, not this dependency closure. Dependency notices describe their own
components and do not relicense NPShips or imported content.

- Original MIT and the independent MDK/Gradle/Ponder notices remain unchanged.
- Current Javadoc contains the matching Java21 toolchain's complete GPLv2,
  Assembly Exception and Additional License Information, plus retained jQuery/
  jQueryUI notices. It is not wholly MIT. Historical Windows-pointer findings
  must be checked against these actual bytes, not carried forward automatically.
- PolyForm Shield1.0.0 remains applicable to Sable. NPShips' inspected use is a
  dependent schematic/lifecycle extension, without a replacement physics engine,
  copied Sable implementation or marketing as a substitute. This is the factual
  scope for the permitted-purpose assessment, not a license for competing products
  or redistribution of the Sable bundle. Free distribution alone is insufficient.
- JEI19.64.0.455/MezzConfig0.6.10 are optional development inputs; Deep Seas/Sodium
  remain opt-in external development inputs. Their implementations/assets are
  absent from NPShips release artifacts and the public-source candidate.
- Selected-document import is not a guaranteed lossless legal/resource repack.
  Fresh export requires the content author's explicit rights/license choice;
  source licenses/notices cannot be erased by import or reassigned to Eynny.
- Authored programs, private schematic fixtures, worlds and development history are
  excluded from this public source tree and its source archive.

Minecraft references/API use are distinct from shipping Minecraft itself. The
public candidate excludes game/decompiled sources, game binaries and copied native
assets. The mod and its example are assessed for the present standalone-mod,
non-replacement distribution plan; no paid/monetized business model is assumed.
Keep the Minecraft non-affiliation disclaimer and supply the publisher's public
contact on the eventual release page. This document grants no rights to unknown
materials and is not permission to publish the private working tree.
