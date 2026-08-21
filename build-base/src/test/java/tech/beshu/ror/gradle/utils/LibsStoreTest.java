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

import org.junit.jupiter.api.Test;

import java.util.Map;

class LibsStoreTest {

  @Test
  void readsTheStoreFromTheEnvironment() {
    var store =
        LibsStore.from(
            Map.of(
                    "ROR_S3_ENDPOINT_URL", "https://store.example.com",
                    "ROR_S3_BUCKET", "a-bucket",
                    "ROR_S3_REGION", "eu-central-1",
                    "ROR_S3_PATH_LIBS", "a/prefix",
                    "ROR_S3_ACCESS_KEY_ID", "an-id",
                    "ROR_S3_SECRET_ACCESS_KEY", "a-secret")
                ::get);

    assertEquals("https://store.example.com", store.endpointUrl());
    assertEquals("a-bucket", store.bucket());
    assertEquals("eu-central-1", store.region());
    assertEquals("a/prefix", store.pathPrefix());
    assertEquals("an-id", store.accessKeyId());
    assertEquals("a-secret", store.accessKeySecret());
  }

  @Test
  void fallsBackToTheDefaultsWhenTheEnvironmentSaysNothing() {
    var store = LibsStore.from(name -> null);

    assertEquals("https://dgp.serve.beshu.tech", store.endpointUrl());
    assertEquals("beshu", store.bucket());
    assertEquals("us-east-1", store.region());
    assertEquals("ror/libs", store.pathPrefix());
  }

  // A run gives a job "" for a `vars.X` that was never defined, and that must mean the same as
  // unset — otherwise the store address silently loses its bucket or its prefix.
  @Test
  void treatsABlankValueAsUnset() {
    var store =
        LibsStore.from(
            Map.of(
                    "ROR_S3_ENDPOINT_URL", "",
                    "ROR_S3_BUCKET", "  ",
                    "ROR_S3_PATH_LIBS", "")
                ::get);

    assertEquals("https://dgp.serve.beshu.tech", store.endpointUrl());
    assertEquals("beshu", store.bucket());
    assertEquals("ror/libs", store.pathPrefix());
  }

  // Credentials are the one thing never defaulted: a guessed key fails as an unattributable 403.
  @Test
  void leavesAbsentCredentialsEmpty() {
    var store = LibsStore.from(name -> null);

    assertEquals("", store.accessKeyId());
    assertEquals("", store.accessKeySecret());
  }

  @Test
  void buildsTheRepositoryUrlTheReadSideResolvesFrom() {
    var store = LibsStore.from(name -> null);

    assertEquals("https://dgp.serve.beshu.tech/beshu/ror/libs", store.repositoryUrl());
  }

  // The endpoint may or may not carry a trailing slash, and the prefix may be given with either;
  // neither must reach the URL as a doubled or a missing separator.
  @Test
  void normalisesTheSlashesAroundTheStoreAddress() {
    var store =
        LibsStore.from(
            Map.of(
                    "ROR_S3_ENDPOINT_URL", "https://store.example.com//",
                    "ROR_S3_PATH_LIBS", "/a/prefix/")
                ::get);

    assertEquals("a/prefix", store.pathPrefix());
    assertEquals("https://store.example.com/beshu/a/prefix", store.repositoryUrl());
  }

  @Test
  void handsTheWriteSideTheSameStoreTheReadSideResolves() {
    var store = LibsStore.from(Map.of("ROR_S3_BUCKET", "a-bucket")::get);

    assertEquals(
        Map.of(
            "ROR_S3_TARGET_STORE", "LIBS",
            "ROR_S3_ENDPOINT_URL", "https://dgp.serve.beshu.tech",
            "ROR_S3_ACCESS_KEY_ID", "",
            "ROR_S3_SECRET_ACCESS_KEY", "",
            "ROR_S3_BUCKET", "a-bucket",
            "ROR_S3_REGION", "us-east-1",
            "ROR_S3_PATH_LIBS", "ror/libs"),
        store.uploadEnvironment());
  }
}
