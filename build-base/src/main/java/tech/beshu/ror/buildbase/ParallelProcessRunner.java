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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs a list of external commands in parallel, waits for ALL of them (no fail-fast), and returns
 * their exit codes. On cancellation or error, forcibly kills every process subtree (including
 * descendants) so no orphan processes survive.
 *
 * <p>Uses raw {@link ProcessBuilder}/{@link Process} — not {@code project.exec} or
 * {@code ExecOperations} — because those hide the {@link Process} handle, which we need for
 * {@code process.descendants().forEach(d -> d.destroyForcibly())} to kill ES/Docker grandchildren.
 *
 * <p>Gradle-agnostic: knows nothing about shards or Gradle tasks. Reusable and unit-testable.
 */
public final class ParallelProcessRunner {

    private final File workingDirectory;
    private final List<Command> commands = new ArrayList<>();

    public ParallelProcessRunner(File workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /**
     * Adds a command to run in parallel.
     *
     * @param command the command tokens (e.g. {@code ["./gradlew", "test", "-PshardIndex=0"]})
     * @param outputFile where to redirect stdout+stderr (no pump threads, no interleaving)
     * @return this runner, for chaining
     */
    public ParallelProcessRunner addCommand(List<String> command, File outputFile) {
        commands.add(new Command(command, outputFile));
        return this;
    }

    /**
     * Runs all added commands in parallel, waits for all to finish, and returns their exit codes.
     *
     * <p>A JVM shutdown hook is registered as a backstop for daemon SIGTERM — it forcibly kills
     * every process subtree. The hook is removed on normal completion. Graceful Gradle cancellation
     * (thread interruption) is handled by {@code get()} on the {@link CompletableFuture}, which
     * lands in the {@code finally} kill-all block; the hook only covers the SIGTERM case where
     * the daemon is killed without interrupting the build thread.
     *
     * @return list of exit codes, one per command (same order as added)
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException if a process fails abnormally (not via exit code)
     */
    public List<Integer> run() throws InterruptedException, ExecutionException {
        // CopyOnWriteArrayList: the shutdown hook iterates this list from another thread (SIGTERM);
        // ArrayList would throw ConcurrentModificationException if the hook fires mid-spawn.
        List<Process> processes = new CopyOnWriteArrayList<>();
        List<CompletableFuture<Process>> futures = new ArrayList<>();

        Thread shutdownHook = new Thread(() -> killAll(processes), "parallel-process-runner-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            for (Command cmd : commands) {
                File parentDir = cmd.outputFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                ProcessBuilder pb = new ProcessBuilder(cmd.tokens)
                        .directory(workingDirectory)
                        .redirectErrorStream(true)
                        .redirectOutput(cmd.outputFile);
                Process process = pb.start();
                processes.add(process);
                futures.add(process.onExit());
            }

            // Wait for ALL — no fail-fast. get() (not join()) stays interrupt-responsive so a
            // cancelled build lands in the catch/finally kill-all block.
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();

            List<Integer> exitCodes = new ArrayList<>();
            for (Process p : processes) {
                exitCodes.add(p.exitValue());
            }
            return exitCodes;
        } finally {
            killAll(processes);
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM already shutting down — hook will run anyway
            }
        }
    }

    private static void killAll(List<Process> processes) {
        for (Process p : processes) {
            p.descendants().forEach(d -> d.destroyForcibly());
            p.destroyForcibly();
        }
    }

    private static final class Command {
        final List<String> tokens;
        final File outputFile;

        Command(List<String> tokens, File outputFile) {
            this.tokens = tokens;
            this.outputFile = outputFile;
        }
    }
}