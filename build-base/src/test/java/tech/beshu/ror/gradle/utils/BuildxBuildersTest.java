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

package tech.beshu.ror.gradle.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class BuildxBuildersTest {

  @TempDir Path tempDir;

  @Test
  void theSameConfigAlwaysGivesTheSameBuilder() throws IOException {
    Path config = config("[registry.\"docker.io\"]\n  mirrors = [\"mirror.gcr.io\"]\n");

    assertEquals(
        BuildxBuilders.mirroredBuilder(config.toFile()),
        BuildxBuilders.mirroredBuilder(config.toFile()));
  }

  @Test
  void anotherConfigGivesAnotherBuilder() throws IOException {
    String builder =
        BuildxBuilders.mirroredBuilder(config("mirrors = [\"one.example.com\"]").toFile());

    assertNotEquals(
        builder,
        BuildxBuilders.mirroredBuilder(config("mirrors = [\"two.example.com\"]").toFile()));
  }

  @Test
  void aMirroredBuilderIsNotTheUnmirroredOne() throws IOException {
    String builder = BuildxBuilders.mirroredBuilder(config("mirrors = []").toFile());

    assertNotEquals(BuildxBuilders.UNMIRRORED_BUILDER, builder);
  }

  @Test
  void theBuildersLeftFromAnEarlierConfigAreStale() {
    List<String> stale =
        BuildxBuilders.staleMirroredBuilders(
            """
            default
            ror_kbn_builder
            ror_kbn_builder_mirrored_0123456789ab
            ror_kbn_builder_mirrored_ffffffffffff
            """,
            "ror_kbn_builder_mirrored_ffffffffffff");

    assertEquals(List.of("ror_kbn_builder_mirrored_0123456789ab"), stale);
  }

  @Test
  void aBuilderIsNamedOnceHoweverManyNodesItHas() {
    List<String> stale =
        BuildxBuilders.staleMirroredBuilders(
            "ror_kbn_builder_mirrored_0123456789ab\nror_kbn_builder_mirrored_0123456789ab\n",
            "ror_kbn_builder_mirrored_ffffffffffff");

    assertEquals(List.of("ror_kbn_builder_mirrored_0123456789ab"), stale);
  }

  @Test
  void anOutputWithNoMirroredBuilderLeavesNothingToDrop() {
    assertTrue(
        BuildxBuilders.staleMirroredBuilders("", "ror_kbn_builder_mirrored_ffffffffffff")
            .isEmpty());
    assertTrue(
        BuildxBuilders.staleMirroredBuilders(
                "default\nror_kbn_builder\n", "ror_kbn_builder_mirrored_ffffffffffff")
            .isEmpty());
  }

  private Path config(String content) throws IOException {
    Path file = Files.createTempFile(tempDir, "buildkitd", ".toml");
    Files.writeString(file, content);
    return file;
  }
}
