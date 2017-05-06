package org.elasticsearch.plugin.readonlyrest.acl.domain;

import java.util.Optional;

public enum HttpMethod {
  GET, POST, PUT, DELETE, OPTIONS, HEAD;

  public static Optional<HttpMethod> fromString(String value) {
    switch (value.toLowerCase()) {
      case "get":
        return Optional.of(GET);
      case "post":
        return Optional.of(POST);
      case "put":
        return Optional.of(PUT);
      case "delete":
        return Optional.of(DELETE);
      case "options":
        return Optional.of(OPTIONS);
      case "head":
        return Optional.of(HEAD);
      default:
        return Optional.empty();
    }
  }
}
