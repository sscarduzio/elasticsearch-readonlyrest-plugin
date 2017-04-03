package org.elasticsearch.plugin.readonlyrest.acl;

import java.util.Objects;

public class LoggedUser {

  private final String id;

  public LoggedUser(String id) {
    this.id = id;
  }

  public String getId() {
    return id;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null) return false;
    if (getClass() != obj.getClass()) return false;
    final LoggedUser other = (LoggedUser) obj;
    return Objects.equals(id, other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.id);
  }

  @Override
  public String toString() {
    return id;
  }
}
