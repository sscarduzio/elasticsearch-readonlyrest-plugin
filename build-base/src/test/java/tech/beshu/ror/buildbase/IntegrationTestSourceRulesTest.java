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

  private static final String TERMS_ENUM = "/_terms_enum\"";
  private static final String ASYNC_SEARCH = "/_async_search\"";
  // The start of a Scala def or of a Java method with an access modifier. The timeout rules check
  // each method on its own, so one request with a timeout does not hide one without it.
  private static final Pattern METHOD_START =
      Pattern.compile(
          "(?m)^[ \\t]*(?:(?:private|protected|public|override|final|implicit)[ \\t]+)*def[ \\t]"
              + "|^[ \\t]*(?:public|private|protected)[ \\t][^=;\\n]*\\(");

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
    assertEveryRequestSets(TERMS_ENUM, "\"timeout\":");
  }

  @Test
  void everyAsyncSearchRequestSetsAWaitForCompletionTimeout() {
    assertEveryRequestSets(ASYNC_SEARCH, "\"wait_for_completion_timeout\"");
  }

  @Test
  void theTimeoutRuleChecksEachRequestAndNotOnlyTheFile() {
    String twoRequests =
        String.join(
            "\n",
            "class Manager {",
            "  def withTimeout(index: String) = {",
            "    val request = new HttpPost(client.from(s\"/$index/_terms_enum\"))",
            "    request.setEntity(new StringEntity(\"\"\"{ \"timeout\": \"30s\" }\"\"\"))",
            "  }",
            "  private def withoutTimeout(index: String) = {",
            "    new HttpPost(client.from(s\"/$index/_terms_enum\"))",
            "  }",
            "}");
    assertEquals(
        List.of("Manager.scala:6: private def withoutTimeout(index: String) = {"),
        requestsWithout("Manager.scala", twoRequests, TERMS_ENUM, "\"timeout\":"));
  }

  @Test
  void theTimeoutRuleAcceptsOneTimeoutForTwoPathsOfOneRequest() {
    // The shape of SearchManager.createAsyncSearchRequest.
    String onePerBranch =
        String.join(
            "\n",
            "  private def createRequest(names: List[String]) = new HttpPost(",
            "    client.from(names match {",
            "      case Nil => \"/_async_search\"",
            "      case _   => s\"/${names.mkString(\",\")}/_async_search\"",
            "    }, Map(\"wait_for_completion_timeout\" -> \"30s\")))",
            "  def other() = new HttpGet(client.from(\"/_search\"))");
    assertEquals(
        List.of(),
        requestsWithout(
            "Search.scala", onePerBranch, ASYNC_SEARCH, "\"wait_for_completion_timeout\""));
  }

  @Test
  void theTimeoutRuleSplitsJavaMethods() {
    String javaSource =
        String.join(
            "\n",
            "class Client {",
            "  public HttpPost withTimeout() {",
            "    return post(\"/_terms_enum\", \"\"\"",
            "        {\"timeout\": \"30s\"}\"\"\");",
            "  }",
            "  private HttpPost withoutTimeout() {",
            "    return post(\"/_terms_enum\", \"{}\");",
            "  }",
            "}");
    assertEquals(
        List.of("Client.java:6: private HttpPost withoutTimeout() {"),
        requestsWithout("Client.java", javaSource, TERMS_ENUM, "\"timeout\":"));
  }

  @Test
  void theRulesFindTheKnownCallSites() {
    // Without this, a moved directory or a changed marker makes every rule above pass on nothing.
    assertTrue(constructorArguments().size() >= 2, "ImageFromDockerfile call sites");
    assertTrue(removeImageCalls().size() >= 1, "removeImageCmd call sites");
    assertTrue(requestCount(TERMS_ENUM) >= 1, "_terms_enum call sites");
    assertTrue(requestCount(ASYNC_SEARCH) >= 1, "_async_search call sites");
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

  private static void assertEveryRequestSets(String marker, String required) {
    List<String> violations = new ArrayList<>();
    sourceFiles()
        .forEach(
            file ->
                violations.addAll(
                    requestsWithout(
                        ROOT.relativize(file).toString(), read(file), marker, required)));
    assertEquals(List.of(), violations, "a request to " + marker + " must set " + required);
  }

  // Each method of `source` that builds a request to `marker` and does not set `required`, as
  // "name:line: first line of the method".
  private static List<String> requestsWithout(
      String name, String source, String marker, String required) {
    return methods(source).stream()
        .filter(method -> method.text.contains(marker) && !method.text.contains(required))
        .map(
            method ->
                name + ":" + method.line + ": " + method.text.lines().findFirst().get().trim())
        .collect(Collectors.toList());
  }

  private static long requestCount(String marker) {
    return sourceFiles()
        .flatMap(file -> methods(read(file)).stream())
        .filter(method -> method.text.contains(marker))
        .count();
  }

  // The source cut at each method start. The text before the first method is a method too.
  private static List<Method> methods(String source) {
    List<Integer> starts = new ArrayList<>(List.of(0));
    Matcher matcher = METHOD_START.matcher(source);
    while (matcher.find()) {
      if (matcher.start() > 0) starts.add(matcher.start());
    }
    starts.add(source.length());
    List<Method> found = new ArrayList<>();
    for (int i = 0; i + 1 < starts.size(); i++) {
      int start = starts.get(i);
      int line = (int) source.substring(0, start).chars().filter(c -> c == '\n').count() + 1;
      found.add(new Method(line, source.substring(start, starts.get(i + 1))));
    }
    return found;
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

  private static final class Method {
    final int line;
    final String text;

    Method(int line, String text) {
      this.line = line;
      this.text = text;
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
