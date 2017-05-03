package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import java.util.Optional;

public enum BlockPolicy {
  ALLOW, FORBID;

  public static Optional<BlockPolicy> fromString(String value) {
    switch (value.toLowerCase()) {
      case "allow":
        return Optional.of(ALLOW);
      case "forbid":
        return Optional.of(FORBID);
      default:
        return Optional.empty();
    }
  }
}
