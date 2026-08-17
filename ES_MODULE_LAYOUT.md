# Layout of the ES adapter modules

The `es*x` modules adapt ROR to one ES version range each. Their content is almost the same, so the
common part is kept once, in a **base** per ES major version.

One rule covers every kind of file:

> The base of the major holds the common file. A module can replace any base file with its own copy.
> Such a copy is an **override**.

This holds for the Scala and Java sources, for the `Dockerfile`, and for the module build script.

## Structure

- `es7-base/`, `es8-base/`, `es9-base/` hold, for that major:
  - `src/main/scala/…` — the shared sources
  - `Dockerfile` — the shared image recipe
  - `module.gradle` — the shared build script (JDK, JarHell class, dependencies)
- Each `es*x` module keeps only what is different for its ES version:
  - a source file at the same relative path replaces the base one
  - its own `Dockerfile` replaces the base one
  - its `build.gradle` applies the base script, then changes only what must change
- `es67x` has no base, because it is the only ES 6 module. Its `build.gradle` holds the full config.
- The applicable base sources (base minus the module's overrides) feed each module's `compileScala`
  directly — no staged copies, so a compile error reports the real `es<major>-base/...` path. The
  applicable `Dockerfile` is staged into the image build context. See
  `readonlyrest.plugin-common-conventions.gradle` and `readonlyrest.docker-image-conventions.gradle`.
- In the IDE, each `es<major>-base` is its own Gradle project: open and edit base sources there;
  a per-ES-version error surfaces on that module's `compileScala`.

## Rules

1. A file goes into the base only when **every** module of that major has it. If one module does not
   have the file, the base would add it to that module.
2. For a **completed** major (ES 6, 7 and 8), the base holds the most common shape of the file.
3. For the **active** major (ES 9), the base holds the newest shape. A new module of this major then
   needs the smallest number of overrides.

There is one base per major, and not one base for all versions, because a base that follows the newest
ES version is never complete. Each new ES version pushes the previous shape down into the older
modules as overrides, also into majors that the PR tests do not run. A completed major does not change
again, and a new minor version can change only the modules of its own major.

The same reason applies to the build script and the `Dockerfile`: a change to `es9-base/module.gradle`
cannot break an ES 7 module.

## How to change a file

- To change all modules of one major: change the file in that major's base. Make sure that each module
  of the major still compiles. The ES APIs are different between versions.
- To change one module only: copy the base file into the module, at the same relative path. Then change
  the module copy. For the build script, add the change to the module's `build.gradle`, after the
  `apply from:` line.
- When an override becomes the same as the base file: delete the override.

## How to add a new ES version module

1. Make the module directory with `gradle.properties`, `plugin-metadata/` and a `build.gradle` that
   holds only:

   ```groovy
   plugins {
       id "readonlyrest.plugin-common-conventions"
   }

   apply from: "$rootDir/es<N>-base/module.gradle"
   ```

2. In `gradle.properties`, set `supportedEsVersions`. The build calculates the other data from it.
3. Compile the module. For each error, add an override, or change the base if the change applies to
   all modules of the major.
4. For a new major version: copy the previous major's base to `es<N>-base`. The older modules do not
   change.
