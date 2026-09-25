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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The names of the {@code docker buildx} builders the docker image tasks make.
 *
 * <p>A builder reads {@code --config} once, when it is created. A host that keeps its docker state --
 * a self-hosted agent, a laptop -- then keeps a builder made from an earlier config, and a change to
 * that config does nothing: buildx reuses the builder it already has. So the name carries the config.
 * A mirrored builder holds the hash of the config file it was made from, which makes each version of
 * the file a builder of its own, and tells a mirrored builder from an unmirrored one.
 *
 * <p>The builders left from earlier versions of the config are named here as well, so the caller can
 * drop them instead of collecting one per edit.
 */
public final class BuildxBuilders {

  /** The builder for a build that pulls straight from Docker Hub. It carries no config. */
  public static final String UNMIRRORED_BUILDER = "ror_kbn_builder";

  private static final String MIRRORED_BUILDER_PREFIX = "ror_kbn_builder_mirrored_";

  /** Long enough to tell two versions of a small config apart, short enough to read in a name. */
  private static final int HASH_LENGTH = 12;

  private BuildxBuilders() {}

  /** The builder for a build that pulls through the mirror {@code buildkitConfig} names. */
  public static String mirroredBuilder(File buildkitConfig) {
    return MIRRORED_BUILDER_PREFIX + shortHashOf(buildkitConfig);
  }

  /**
   * The mirrored builders made from another config than the one in use, taken from the output of
   * {@code docker buildx ls --format '{{.Builder.Name}}'}. That format prints the name of the builder
   * once for each of its nodes, never the name of a node, so the same name can arrive more than once.
   *
   * <p>Empty for the output of an older buildx that does not know the format, and for a builder this
   * class did not name.
   */
  public static List<String> staleMirroredBuilders(String buildxLsOutput, String builderInUse) {
    return Arrays.stream(buildxLsOutput.split("\\R"))
        .map(String::trim)
        .filter(name -> name.startsWith(MIRRORED_BUILDER_PREFIX) && !name.equals(builderInUse))
        .distinct()
        .collect(Collectors.toList());
  }

  private static String shortHashOf(File file) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.substring(0, HASH_LENGTH);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing", e);
    }
  }
}
