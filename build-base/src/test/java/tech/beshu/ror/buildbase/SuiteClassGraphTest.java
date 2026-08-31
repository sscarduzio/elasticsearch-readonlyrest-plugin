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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

class SuiteClassGraphTest {

  // Fixture hierarchy compiled into THIS test's class dir:
  interface Marker {}

  interface MidTrait extends Marker {}

  abstract static class BaseWithMarker implements MidTrait {}

  static class DirectUser implements Marker {}

  static class TransitiveUser extends BaseWithMarker {}

  static class Unrelated {}

  // Parent comes from OUTSIDE the scanned tree (java.util): the edge must prune, not crash.
  static class ExternalParent extends java.util.ArrayList<String> {}

  private static final String MARKER = Marker.class.getName();

  @Test
  void findsDirectAndTransitiveSubtypesAndIgnoresTheRest() throws Exception {
    Set<String> found =
        SuiteClassGraph.subtypesOf(
            MARKER,
            List.of(ownClassesDir()),
            List.of(
                DirectUser.class.getName(),
                TransitiveUser.class.getName(),
                Unrelated.class.getName(),
                ExternalParent.class.getName()));
    assertEquals(Set.of(DirectUser.class.getName(), TransitiveUser.class.getName()), found);
  }

  @Test
  void candidateAbsentFromTheScannedTreeIsNotASubtype() throws IOException, URISyntaxException {
    Set<String> found =
        SuiteClassGraph.subtypesOf(
            MARKER, List.of(ownClassesDir()), List.of("com.example.DoesNotExist"));
    assertEquals(Set.of(), found);
  }

  private static Path ownClassesDir() throws URISyntaxException {
    return Paths.get(
        SuiteClassGraphTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  }
}
