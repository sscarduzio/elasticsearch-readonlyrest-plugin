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

import org.gradle.api.GradleException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * What the build reads over the network. Every connection is opened here so that none of them can be left
 * without a timeout: a build hanging on an unresponsive host is worse than one that fails.
 */
final class Downloads {

  private static final int TIMEOUT_MS = 30_000;

  private Downloads() {}

  /** The content of {@code url} as text. */
  static String read(String url) {
    return find(url)
        .orElseThrow(() -> new GradleException("Cannot read " + url + ": not published"));
  }

  /** The content of {@code url}, or empty when there is nothing at it. Anything else still throws. */
  static Optional<String> find(String url) {
    try (InputStream content = open(url)) {
      return Optional.of(new String(content.readAllBytes(), StandardCharsets.UTF_8));
    } catch (FileNotFoundException e) {
      // What both an HTTP 404 and a missing file:// path raise.
      return Optional.empty();
    } catch (IOException | IllegalArgumentException e) {
      throw new GradleException("Cannot read " + url + ": " + e, e);
    }
  }

  /** The content of {@code url} as a stream, for what is too big to hold in memory. */
  static InputStream open(String url) throws IOException {
    URLConnection connection = URI.create(url).toURL().openConnection();
    connection.setConnectTimeout(TIMEOUT_MS);
    connection.setReadTimeout(TIMEOUT_MS);
    return connection.getInputStream();
  }
}
