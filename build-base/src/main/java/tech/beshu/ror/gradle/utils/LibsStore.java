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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Where the ROR libs store lives: the S3-compatible bucket ROR mirrors ES jars into, for versions Elastic has
 * released but not yet published to Maven Central.
 *
 * <p>Two sides of the build address that one location, months apart in wall-clock time. The <b>read</b> side is
 * every plugin build: {@code readonlyrest.plugin-common-conventions} declares the store as a Maven repository,
 * so the es*x modules compile against whatever is in it. The <b>write</b> side runs once per new ES release:
 * {@code ror-tools:uploadArtifactsFromEsBinaries} shells out to {@code ci/upload-files-to-s3.sh} with the store
 * in the environment.
 *
 * <p>They must agree, and nothing checks that they do. When they disagreed the symptom appeared far from the
 * cause: jars land in a path the repository does not serve, and the next plugin build fails to resolve
 * {@code org.elasticsearch:elasticsearch:X.Y.Z} — which reads like a broken build, not like a mismatched bucket.
 * So both sides read the coordinates from here, and the defaults exist once.
 *
 * <p>The credentials are deliberately part of this record but never defaulted: an absent key must reach the
 * uploader as empty (it then attempts an anonymous upload and fails loudly) rather than as something guessed.
 */
public record LibsStore(
    String endpointUrl,
    String bucket,
    String region,
    String pathPrefix,
    String accessKeyId,
    String accessKeySecret) {

  private static final String DEFAULT_ENDPOINT_URL = "https://dgp.serve.beshu.tech";
  private static final String DEFAULT_BUCKET = "beshu";
  private static final String DEFAULT_REGION = "us-east-1";
  private static final String DEFAULT_PATH_PREFIX = "ror/libs";

  /** The store as the environment describes it, falling back to the defaults above. */
  public static LibsStore fromEnv() {
    return from(System::getenv);
  }

  /** {@link #fromEnv()} over an arbitrary environment, so the resolution is testable. */
  public static LibsStore from(Function<String, String> env) {
    return new LibsStore(
        valueOrDefault(env, "ROR_S3_ENDPOINT_URL", DEFAULT_ENDPOINT_URL),
        valueOrDefault(env, "ROR_S3_BUCKET", DEFAULT_BUCKET),
        valueOrDefault(env, "ROR_S3_REGION", DEFAULT_REGION),
        trimSlashes(valueOrDefault(env, "ROR_S3_PATH_LIBS", DEFAULT_PATH_PREFIX)),
        valueOrDefault(env, "ROR_S3_ACCESS_KEY_ID", ""),
        valueOrDefault(env, "ROR_S3_SECRET_ACCESS_KEY", ""));
  }

  /** The URL a Gradle {@code maven {}} repository resolves the mirrored jars and POMs from. */
  public String repositoryUrl() {
    return trimTrailingSlashes(endpointUrl) + "/" + bucket + "/" + pathPrefix;
  }

  /**
   * The store as {@code ci/upload-files-to-s3.sh} expects to find it in the environment. One
   * credential set serves every store, so {@code ROR_S3_TARGET_STORE} selects only which key
   * prefix the upload lands under — {@code ROR_S3_PATH_LIBS} rather than the artifacts one.
   */
  public Map<String, String> uploadEnvironment() {
    var environment = new LinkedHashMap<String, String>();
    environment.put("ROR_S3_TARGET_STORE", "LIBS");
    environment.put("ROR_S3_ENDPOINT_URL", endpointUrl);
    environment.put("ROR_S3_ACCESS_KEY_ID", accessKeyId);
    environment.put("ROR_S3_SECRET_ACCESS_KEY", accessKeySecret);
    environment.put("ROR_S3_BUCKET", bucket);
    environment.put("ROR_S3_REGION", region);
    environment.put("ROR_S3_PATH_LIBS", pathPrefix);
    return environment;
  }

  private static String valueOrDefault(
      Function<String, String> env, String name, String defaultValue) {
    var value = env.apply(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private static String trimSlashes(String value) {
    return trimTrailingSlashes(value).replaceAll("^/+", "");
  }

  private static String trimTrailingSlashes(String value) {
    return value.replaceAll("/+$", "");
  }
}
