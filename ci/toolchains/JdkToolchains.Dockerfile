# Self-contained CI build image: bundles every TOOL + a primed Gradle home, so the build
# never downloads a JDK, the Gradle distribution, an apt package, or a build dependency from
# a flaky external CDN at build time.
#
# Background — the build reaches out to several GitHub-hosted CDNs, all of which have returned
# transient 504s and failed the build even though the code was fine:
#   * JDK toolchains 8/11/21  <- api.foojay.io -> github.com/adoptium
#   * the Gradle distribution <- services.gradle.org -> github.com/gradle/gradle-distributions
#   * git/curl                <- ubuntu apt repos
#   * build dependencies      <- gradle plugin portal / Maven Central / jitpack / S3 / elastic
#
# Why these JDKs (one Java toolchain per supported target):
#   * JDK  8  -> :audit            (cross-built lib published to Maven Central; Java-8 floor)
#   * JDK 11  -> :core + ES 6.7-7.x adapters + ror-tools*/tests-utils (ES 6.7-7.x bundle Java 11)
#   * JDK 17  -> ES 8.x adapters + :benchmarks + :integration-tests   (ES 8.x bundles Java 17)
#   * JDK 21  -> ES 9.x adapters                                      (ES 9.x bundles Java 21)
#
# Build context MUST be the repo root (the build needs settings.gradle/*/build.gradle to
# resolve dependencies during the priming step). Build & push via the manual
# BUILD_TOOLCHAINS_IMAGE stage in azure-pipelines.yml (actionToPerform=build_toolchains_image),
# or locally:
#
#   docker build -f ci/toolchains/JdkToolchains.Dockerfile -t beshultd/ror-ci-toolchains:jdk-8-11-17-21-gradle-9.2.1 .
#   docker push beshultd/ror-ci-toolchains:jdk-8-11-17-21-gradle-9.2.1
#
# (BuildKit is required: the build context is filtered by the sibling
# ci/toolchains/JdkToolchains.Dockerfile.dockerignore file.)
#
# The tag encodes the two things that force a rebuild: the JDK set and the Gradle version.
# Bump it (and azure-pipelines.yml `container:`) when: a supported ES major adds a new JVM, the
# Gradle version changes (gradle/wrapper/gradle-wrapper.properties), or dependencies drift enough
# that the primed offline cache misses often.

# ---- JDK sources (all temurin images install to /opt/java/openjdk) ----------------------
FROM eclipse-temurin:8-jdk   AS jdk8
FROM eclipse-temurin:11-jdk  AS jdk11
FROM eclipse-temurin:21-jdk  AS jdk21
# Docker CLIENT source (static binary — distro-agnostic, no apt repo/codename needed)
FROM docker:27.3.1-cli AS docker-cli

FROM eclipse-temurin:17-jdk AS base

# apt tools the pipeline needs (was: `apt-get install -y git curl` on every run).
RUN apt-get update \
 && apt-get install -y --no-install-recommends git curl ca-certificates unzip \
 && rm -rf /var/lib/apt/lists/*

# Docker CLIENT only: UPLOAD_PRE_ROR/RELEASE_ROR run `docker login` / `publishEsRorDockerImage`
# from inside this container and talk to the agent host's Docker daemon over the bind-mounted
# /var/run/docker.sock (the self-hosted az-ror-es agents mount it).
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
RUN docker --version

# the other three JDK toolchains alongside the base image's JDK 17
COPY --from=jdk8  /opt/java/openjdk /opt/java/jdk8
COPY --from=jdk11 /opt/java/openjdk /opt/java/jdk11
COPY --from=jdk21 /opt/java/openjdk /opt/java/jdk21

ENV GRADLE_USER_HOME=/opt/gradle-home

# ---- prime a baked GRADLE_USER_HOME: Gradle distribution + plugins + dependencies ----------
# Done in a separate stage so the repo sources (`COPY . /tmp/ror-src`) never become a layer of
# the final image — only the primed /opt/gradle-home is copied over.
# Resolve-only priming (NOT a full compile -- that spawned ~6 Scala-compiler JVMs and needed
# >6 GiB). Two light steps:
#   1) `help` downloads the Gradle distribution into the wrapper/dists layout ./gradlew expects
#      (so CI never re-downloads it -> kills the services.gradle.org/github 504s) and applies
#      every project's plugins, resolving plugin classpaths into the cache.
#   2) `resolveCiDependencies` (registered by the ci/toolchains/resolve-deps.init.gradle init
#      script -- which has zero footprint on the shipped build) resolves all resolvable
#      configurations of all projects into caches/modules-2 without compiling.
# Both run with toolchain auto-download off (all JDKs are already baked in). Anything that still
# misses at CI time would need the network, which is why the pipeline keeps `--offline`
# overridable via ROR_GRADLE_OFFLINE=false to refresh the cache.
FROM base AS gradle-prime
# Bake the image's JDK locations into the GRADLE_USER_HOME gradle.properties. This is read reliably
# at CI time (installations.paths via GRADLE_OPTS is NOT reliably honoured), applies only to jobs
# that use this baked Gradle home (the Linux container jobs), and is never seen by the bare Windows
# jobs (they use a workspace-local GRADLE_USER_HOME) — so the Linux paths can't break Windows.
RUN mkdir -p /opt/gradle-home \
 && printf '%s\n' \
      'org.gradle.java.installations.auto-download=false' \
      'org.gradle.java.installations.paths=/opt/java/jdk8,/opt/java/jdk11,/opt/java/openjdk,/opt/java/jdk21' \
      > /opt/gradle-home/gradle.properties
COPY . /tmp/ror-src
RUN cd /tmp/ror-src \
 && ./gradlew --no-daemon --console=plain \
      --init-script ci/toolchains/resolve-deps.init.gradle \
      -Dorg.gradle.java.installations.auto-download=false \
      -Dorg.gradle.java.installations.paths=/opt/java/jdk8,/opt/java/jdk11,/opt/java/openjdk,/opt/java/jdk21 \
      help resolveCiDependencies \
 # Cache the full classpath of every task the pipeline runs with `--offline` (run-pipeline.sh):
 #   compile_codebase_check -> `classes`     (every module's compile deps, incl. per-ES-version
 #                                            transport-netty4 etc. — and the Scala compiler bridge,
 #                                            resolved via a detached config only when a compile runs)
 #   core_tests             -> `testClasses` (every module's test-only deps, without running tests)
 #   audit_build_check      -> `audit:crossBuildAssemble` (2.11/2.12/2.13/3 bridges + cross deps)
 # Integration tests are NOT offline (they fetch ES binaries + private libs at runtime), so not primed here.
 && ./gradlew --no-daemon --console=plain --max-workers=1 \
      -Dorg.gradle.parallel=false -Dorg.gradle.workers.max=1 \
      -Dorg.gradle.java.installations.auto-download=false \
      -Dorg.gradle.java.installations.paths=/opt/java/jdk8,/opt/java/jdk11,/opt/java/openjdk,/opt/java/jdk21 \
      classes testClasses \
      :audit:crossBuildAssemble \
 # Self-validate OFFLINE the exact classpaths the --offline CI tasks need, so an incomplete cache fails
 # THIS image build instead of a CI run later. core/audit build outputs are wiped first so the offline
 # pass genuinely RE-COMPILES (exercising offline Scala compiler-bridge resolution) instead of
 # short-circuiting on UP-TO-DATE outputs from the online prime above.
 && rm -rf /tmp/ror-src/core/build /tmp/ror-src/audit/build \
 && ./gradlew --no-daemon --console=plain --offline --max-workers=1 \
      -Dorg.gradle.parallel=false -Dorg.gradle.workers.max=1 \
      classes testClasses :audit:crossBuildAssemble \
 # Sentinel marking this Gradle home as the CI-baked offline cache. RorPluginGradleProject keys its
 # nested-build offline mode on this file (NOT on bare GRADLE_USER_HOME existence, which would force
 # --offline on any dev with a relocated Gradle home). Written only after offline validation passed.
 && touch "$GRADLE_USER_HOME/.ror-ci-baked" \
 && rm -rf "$GRADLE_USER_HOME"/caches/*/scripts "$GRADLE_USER_HOME"/daemon \
 && find "$GRADLE_USER_HOME" -name "*.lock" -delete 2>/dev/null || true \
 # The Azure container job may run as a non-root user; make the baked Gradle home writable so
 # Gradle can write lockfiles/outputs into it at CI time without needing a copy-to-workspace step.
 && chmod -R a+rwX "$GRADLE_USER_HOME"

# ---- final image: toolchains + the primed Gradle home, WITHOUT the repo sources ------------
FROM base
COPY --from=gradle-prime /opt/gradle-home /opt/gradle-home
# At CI time: point GRADLE_USER_HOME at /opt/gradle-home and run build tasks with `--offline`
# (download-free tasks only — NOT integration tests / buildRorPlugin, which fetch ES binaries).
