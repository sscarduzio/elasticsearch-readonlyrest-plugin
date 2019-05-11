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
package tech.beshu.ror.utils.misc;

import com.google.common.base.Strings;
import org.testcontainers.shaded.com.fasterxml.jackson.core.util.VersionUtil;

public class Version {

  private Version() {}

  public static boolean greaterOrEqualThan(String esVersion, int maj, int min, int patchLevel) {
    if (Strings.isNullOrEmpty(esVersion)) {
      throw new IllegalArgumentException("invalid esVersion: " + esVersion);
    }
    return VersionUtil
        .parseVersion(esVersion, "x", "y")
        .compareTo(
            new org.testcontainers.shaded.com.fasterxml.jackson.core.Version(maj, min, patchLevel, "", "x", "y")) >= 0;
  }

}
