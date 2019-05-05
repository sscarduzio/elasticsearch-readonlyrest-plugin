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
