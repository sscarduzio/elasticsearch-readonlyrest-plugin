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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Rules for the integration-test sources (tests-utils and integration-tests) that keep known CI
 * flakes from coming back. The build-base test task declares these directories as inputs.
 */
class IntegrationTestSourceRulesTest {

  // Gradle runs this test in the build-base directory.
  private static final Path ROOT = Paths.get("").toAbsolutePath().getParent();

  private static final List<Path> SOURCE_DIRS =
      List.of(ROOT.resolve("tests-utils/src/main"), ROOT.resolve("integration-tests/src/test"));

  // Ryuk (deleteOnExit) and a forced or pruning image removal also remove the untagged parent
  // layers. Other builds use those layers as their build cache at the same time, and then fail with
  // "No such image".
  private static final String IMAGE_FROM_DOCKERFILE = "new ImageFromDockerfile(";
  private static final Pattern LAST_ARGUMENT_IS_FALSE =
      Pattern.compile(",\\s*(/\\*[^*]*\\*/\\s*)?false\\s*$");
  private static final Pattern REMOVE_IMAGE_CMD = Pattern.compile("removeImageCmd\\(.*");

  @Test
  void theSourceDirectoriesExist() {
    SOURCE_DIRS.forEach(dir -> assertTrue(Files.isDirectory(dir), "missing: " + dir));
  }

  @Test
  void everyImageFromDockerfileTurnsOffDeleteOnExit() {
    List<String> violations =
        constructorArguments().stream()
            .filter(call -> !LAST_ARGUMENT_IS_FALSE.matcher(call.text).find())
            .map(Match::describe)
            .collect(Collectors.toList());
    assertEquals(
        List.of(),
        violations,
        "pass deleteOnExit = false to ImageFromDockerfile (see DockerImageCreator)");
  }

  @Test
  void everyImageRemovalKeepsTheParentLayers() {
    List<String> violations =
        removeImageCalls().stream()
            .filter(
                call ->
                    !call.text.contains(".withNoPrune(true)")
                        || !call.text.contains(".withForce(false)"))
            .map(Match::describe)
            .collect(Collectors.toList());
    assertEquals(
        List.of(),
        violations,
        "remove an image with .withForce(false).withNoPrune(true) (see EsContainer.removeImage)");
  }

  // ES applies a 1 s default to both. On a busy CI runner ES then returns 200 with an empty result.
  @Test
  void everyTermsEnumRequestSetsATimeout() {
    assertEveryFileThatMentions("/_terms_enum\"", "\"timeout\":");
  }

  @Test
  void everyAsyncSearchRequestSetsAWaitForCompletionTimeout() {
    assertEveryFileThatMentions("/_async_search\"", "\"wait_for_completion_timeout\"");
  }

  @Test
  void theRulesFindTheKnownCallSites() {
    // Without this, a moved directory or a changed marker makes every rule above pass on nothing.
    assertTrue(constructorArguments().size() >= 2, "ImageFromDockerfile call sites");
    assertTrue(removeImageCalls().size() >= 1, "removeImageCmd call sites");
    assertTrue(filesContaining("/_terms_enum\"").size() >= 1, "_terms_enum call sites");
    assertTrue(filesContaining("/_async_search\"").size() >= 1, "_async_search call sites");
  }

  @Test
  void theDeleteOnExitRuleTellsTrueFromFalse() {
    assertTrue(LAST_ARGUMENT_IS_FALSE.matcher("tag, /* deleteOnExit = */ false").find());
    assertTrue(LAST_ARGUMENT_IS_FALSE.matcher("\n  \"a:\" + hash(files), false").find());
    assertTrue(!LAST_ARGUMENT_IS_FALSE.matcher("tag, /* deleteOnExit = */ true").find());
    assertTrue(!LAST_ARGUMENT_IS_FALSE.matcher("tag").find());
    assertTrue(!LAST_ARGUMENT_IS_FALSE.matcher("").find());
  }

  // The text between the parentheses of each `new ImageFromDockerfile(...)`.
  private static List<Match> constructorArguments() {
    List<Match> found = new ArrayList<>();
    sourceFiles()
        .forEach(
            file -> {
              String source = read(file);
              int start = source.indexOf(IMAGE_FROM_DOCKERFILE);
              while (start >= 0) {
                int open = start + IMAGE_FROM_DOCKERFILE.length();
                int depth = 1;
                int end = open;
                while (end < source.length() && depth > 0) {
                  char c = source.charAt(end);
                  if (c == '(') depth++;
                  if (c == ')') depth--;
                  end++;
                }
                found.add(new Match(file, source.substring(open, end - 1)));
                start = source.indexOf(IMAGE_FROM_DOCKERFILE, end);
              }
            });
    return found;
  }

  // The rest of the line of each `removeImageCmd(` call.
  private static List<Match> removeImageCalls() {
    List<Match> found = new ArrayList<>();
    sourceFiles()
        .forEach(
            file -> {
              Matcher matcher = REMOVE_IMAGE_CMD.matcher(read(file));
              while (matcher.find()) {
                found.add(new Match(file, matcher.group()));
              }
            });
    return found;
  }

  private static void assertEveryFileThatMentions(String marker, String required) {
    List<String> violations =
        filesContaining(marker).stream()
            .filter(file -> !read(file).contains(required))
            .map(file -> ROOT.relativize(file).toString())
            .collect(Collectors.toList());
    assertEquals(List.of(), violations, "a request to " + marker + " must set " + required);
  }

  private static List<Path> filesContaining(String marker) {
    return sourceFiles().filter(file -> read(file).contains(marker)).collect(Collectors.toList());
  }

  private static Stream<Path> sourceFiles() {
    List<Path> files = new ArrayList<>();
    for (Path dir : SOURCE_DIRS) {
      try (Stream<Path> walk = Files.walk(dir)) {
        walk.filter(Files::isRegularFile)
            .filter(f -> f.toString().endsWith(".scala") || f.toString().endsWith(".java"))
            .forEach(files::add);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return files.stream();
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static final class Match {
    final Path file;
    final String text;

    Match(Path file, String text) {
      this.file = file;
      this.text = text;
    }

    String describe() {
      return ROOT.relativize(file) + ": " + text.trim();
    }
  }
}
