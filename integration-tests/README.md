# Integration tests

Docker-based suites (TestContainers), one ES module at a time:

```bash
./gradlew integration-tests:test -PesModule=<es_module>
```

An `<es_module>` is one of the `esNNx` directories in the repository root — `es67x`, `es717x`,
`es818x`, `es94x` and so on. Each one supports a range of ES versions, and the suites run against
the newest version in that range unless `-PesVersion` says otherwise. To list the modules of a
given ES major:

```bash
./gradlew printEsModules -PesMajor=8 --quiet
```

Omit `-PesModule` to run against the module that publishes the newest supported ES version.

Suites run serially inside a worker JVM, because they share one mutable singleton ES. Parallelism
comes from sharding: K independent invocations over a disjoint partition of the suites.

Each shard runs its share in up to two steps, so it frees the shared ES as soon as the suites that
need it are done, instead of holding it idle until the shard ends:

1. `integration-tests:sharedEsSuitesTest` — the suites that use the shared ES. The ES stops when
   this task's JVM exits.
2. `integration-tests:test` — the remaining suites, in a fresh JVM. Each one starts its own
   cluster, and this JVM never starts a shared ES.

A shard whose partition holds no suite for one of the steps disables that step, so a shard runs one
task or two. `shardedTest` starts both steps for every shard, in that order. Without sharding,
`test` alone still runs every suite.

## Knobs

The variables and properties below are optional.

### Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `ROR_ES_SLIM_MODULES` | `true` | Strip the ES modules no suite uses from the test image. This applies to the official Elasticsearch Docker image only — the Ubuntu image with ES from apt, and the native Windows process, keep every module. Set `false` to run against a stock ES image, e.g. when adding a suite for a feature whose module the slim image drops. |
| `ROR_ES_JDWP` | `true` | Add a JDWP agent to every ES test container, so a debugger can attach on port 8000. |
| `ROR_ES_CONTAINER_MEMORY_MB` | `2048` | Hard memory limit per ES container. |
| `ROR_HEAVY_SUITE_PERMITS` | unset (no limit) | Cap on multi-container ("heavy") suites running at once, enforced across shard JVMs via file locks. |
| `ROR_BALANCED_SHARDS` | `false` | Pack shards by measured duration (`suite-timings.json`) instead of name-hash. |
| `IT_PARALLELISM` | `1` | Number of parallel shards `ci/run-pipeline.sh` starts; becomes `-PshardCount`. |
| `IT_ORCHESTRATOR_JVMARGS` | `-Xmx2048m -XX:MaxMetaspaceSize=512m` | Heap of the Gradle process that `ci/run-pipeline.sh` uses to spawn the shards. |

### Gradle properties

| Property | Default | Meaning |
|---|---|---|
| `-PesModule` | newest supported | The `esNNx` module whose ES version the suites run against (see above). |
| `-PesVersion` | module's newest | Exact ES version override. |
| `-PshardCount` / `-PshardIndex` | `1` / — | Suite sharding: a disjoint partition of the suites across K independent invocations. `shardedTest -PshardCount=K` spawns the K children. |
| `-PitTestHeap` | `512m` | Test-worker JVM heap. |
