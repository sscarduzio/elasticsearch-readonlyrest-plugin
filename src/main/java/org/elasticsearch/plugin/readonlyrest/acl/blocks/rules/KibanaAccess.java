package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import java.util.Optional;

public enum KibanaAccess {
  RO, RW, RO_STRICT, ADMIN;

  public static Optional<KibanaAccess> fromString(String value) {
    switch (value.toLowerCase()) {
      case "ro_strict":
        return Optional.of(RO_STRICT);
      case "ro":
        return Optional.of(RO);
      case "rw":
        return Optional.of(RW);
      case "admin":
        return Optional.of(ADMIN);
      default:
        return Optional.empty();
    }
  }
}
