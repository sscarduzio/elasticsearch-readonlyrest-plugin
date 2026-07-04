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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

class ParallelProcessRunnerTest {

  @TempDir File tempDir;

  @Test
  void runSingleCommandReturnsExitCode()
      throws IOException, InterruptedException, ExecutionException {
    File logFile = new File(tempDir, "out.log");
    ParallelProcessRunner runner = new ParallelProcessRunner(tempDir);
    runner.addCommand(Arrays.asList("true"), logFile);

    List<Integer> exitCodes = runner.run();

    assertEquals(1, exitCodes.size());
    assertEquals(0, exitCodes.get(0));
    assertTrue(logFile.exists());
  }

  @Test
  void runMultipleCommandsInParallel()
      throws IOException, InterruptedException, ExecutionException {
    File log1 = new File(tempDir, "shard-0.log");
    File log2 = new File(tempDir, "shard-1.log");
    ParallelProcessRunner runner = new ParallelProcessRunner(tempDir);
    runner.addCommand(Arrays.asList("true"), log1);
    runner.addCommand(Arrays.asList("true"), log2);

    List<Integer> exitCodes = runner.run();

    assertEquals(2, exitCodes.size());
    assertEquals(0, exitCodes.get(0));
    assertEquals(0, exitCodes.get(1));
  }

  @Test
  void runFailingCommandReturnsNonZeroExitCode()
      throws IOException, InterruptedException, ExecutionException {
    File logFile = new File(tempDir, "out.log");
    ParallelProcessRunner runner = new ParallelProcessRunner(tempDir);
    runner.addCommand(Arrays.asList("false"), logFile);

    List<Integer> exitCodes = runner.run();

    assertEquals(1, exitCodes.size());
    assertTrue(exitCodes.get(0) != 0);
  }

  @Test
  void runWaitsForAllCommandsNoFailFast()
      throws IOException, InterruptedException, ExecutionException {
    File log1 = new File(tempDir, "shard-0.log");
    File log2 = new File(tempDir, "shard-1.log");
    ParallelProcessRunner runner = new ParallelProcessRunner(tempDir);
    // One succeeds, one fails -- runner should still return both exit codes, not throw
    runner.addCommand(Arrays.asList("true"), log1);
    runner.addCommand(Arrays.asList("false"), log2);

    List<Integer> exitCodes = runner.run();

    assertEquals(2, exitCodes.size());
    assertEquals(0, exitCodes.get(0));
    assertTrue(exitCodes.get(1) != 0);
  }

  @Test
  void runRedirectsOutputToFile() throws IOException, InterruptedException, ExecutionException {
    File logFile = new File(tempDir, "out.log");
    ParallelProcessRunner runner = new ParallelProcessRunner(tempDir);
    runner.addCommand(Arrays.asList("echo", "hello-from-shard"), logFile);

    runner.run();

    String content = Files.readString(logFile.toPath());
    assertTrue(content.contains("hello-from-shard"));
  }

  @Test
  void runCreatesOutputDirIfMissing() throws IOException, InterruptedException, ExecutionException {
    File logFile = new File(tempDir, "nested/deep/out.log");
    ParallelProcessRunner runner = new ParallelProcessRunner(tempDir);
    runner.addCommand(Arrays.asList("true"), logFile);

    runner.run();

    assertTrue(logFile.exists());
  }
}
