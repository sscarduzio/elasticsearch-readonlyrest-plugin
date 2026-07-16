/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.buildbase;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Gradle task that orchestrates sharded integration tests: spawns K parallel
 * {@code ./gradlew integration-tests:test -PshardIndex=i} JVMs (one ES container each),
 * waits for all, and fails if any shard exits non-zero.
 *
 * <p>Each shard's stdout+stderr is redirected to its own file under
 * {@code build/sharded-logs/shard-<i>.log} -- no pump threads, no mid-line interleaving.
 *
 * <p>Uses raw {@link ParallelProcessRunner} (not {@code project.exec}) because we need
 * {@code Process.descendants().destroyForcibly()} to kill the ES/Docker grandchildren on
 * cancellation. {@code --no-daemon} is mandatory: it ensures each shard's Gradle JVM is a
 * direct child process whose descendant tree we can reap -- a daemon would detach and survive.
 */
public abstract class ShardedGradlewTest extends DefaultTask {

  /**
   * Builds and runs the shard commands.
   *
   * @throws GradleException if any shard exits non-zero, or if the run is interrupted
   */
  @TaskAction
  public void runShards() {
    int shardCount = shardCountValue();
    File projectDir = getProject().getRootProject().getProjectDir();
    File logDir =
        new File(getProject().getLayout().getBuildDirectory().getAsFile().get(), "sharded-logs");

    ParallelProcessRunner runner = new ParallelProcessRunner(projectDir);

    for (int i = 0; i < shardCount; i++) {
      // Platform-correct wrapper invocation lives in GradlewCommand (cmd.exe /c on Windows).
      // ProcessHandle.descendants().destroyForcibly() in ParallelProcessRunner is cross-platform,
      // so cancellation reaping works the same on both OSes.
      List<String> cmd = new ArrayList<>(GradlewCommand.forHost(projectDir));
      cmd.add("--no-daemon"); // mandatory for descendant-tree integrity (see class javadoc)
      cmd.add("integration-tests:test");
      cmd.add("-PesModule=" + esModuleValue());
      String esVer = esVersionValue();
      if (esVer != null && !esVer.isEmpty()) {
        cmd.add("-PesVersion=" + esVer);
      }
      cmd.add("-PshardCount=" + shardCount);
      cmd.add("-PshardIndex=" + i);
      // Child daemons only orchestrate one 512m test-worker JVM + docker containers; without this
      // they inherit gradle.properties' -Xmx6144m, reserving ~6GB x K on a 16GB runner — the real
      // memory ceiling behind the k=5/k=6 host-OOM deaths, not Elasticsearch itself.
      cmd.add("-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m");
      // THIS task's dependsOn already ran prebuildEsImage once, before any shard spawned. The
      // child's own prebuildEsImage dependency is NOT a cache hit: it launches a nested Tooling-API
      // build ("Assembling ROR ...") and K concurrent nested builds in one workspace race each
      // other to death. Exclude it — shards consume the image the parent prebuilt.
      cmd.add("-x");
      cmd.add("integration-tests:prebuildEsImage");

      File shardLog = new File(logDir, "shard-" + i + ".log");
      runner.addCommand(cmd, shardLog);
    }

    try {
      List<Integer> exitCodes = runner.run();
      if (exitCodes.stream().anyMatch(code -> code != 0)) {
        throw new GradleException(
            "Sharded integration tests failed -- per-shard exit codes: " + exitCodes);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GradleException("Sharded integration tests were interrupted", e);
    } catch (ExecutionException e) {
      throw new GradleException("Sharded integration tests failed abnormally", e);
    } catch (IOException e) {
      throw new GradleException("Failed to start sharded integration test processes", e);
    }
  }

  /**
   * Number of parallel shards to run. Maps to {@code -PshardCount} on the command line.
   */
  @Internal
  public abstract org.gradle.api.provider.Property<Integer> getShardCount();

  /**
   * ES module to test (e.g. {@code es94x}). Maps to {@code -PesModule}.
   */
  @Internal
  public abstract org.gradle.api.provider.Property<String> getEsModule();

  /**
   * Explicit ES version override (e.g. {@code 8.19.11}), or null if not specified.
   */
  @Internal
  public abstract org.gradle.api.provider.Property<String> getEsVersion();

  private int shardCountValue() {
    return getShardCount().getOrElse(1);
  }

  private String esModuleValue() {
    return getEsModule().getOrElse("es94x");
  }

  private String esVersionValue() {
    return getEsVersion().getOrElse((String) null);
  }
}
