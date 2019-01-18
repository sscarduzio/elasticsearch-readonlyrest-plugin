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

package tech.beshu.ror.acl.domain;

import com.google.common.collect.Sets;
import tech.beshu.ror.commons.Constants;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class __old_LoggedUser {

  private final String id;
  private final LinkedHashSet<String> availableGroups = Sets.newLinkedHashSet();
  private Optional<String> currentGroup = Optional.empty();

  public __old_LoggedUser(String id) {
    this.id = id;
  }

  public String getId() {
    return id;
  }

  public void addAvailableGroups(Set<String> groups) {
    availableGroups.addAll(groups);
  }

  public Optional<String> getCurrentGroup() {
    return currentGroup;
  }

  public void setCurrentGroup(String currentGroup) {
    this.currentGroup = Optional.ofNullable(currentGroup);
  }

  public Optional<String> resolveCurrentGroup(Map<String, String> requestHeaders) {
    currentGroup  = Optional.ofNullable(requestHeaders.get(Constants.HEADER_GROUP_CURRENT));
    return currentGroup;
  }

  public Set<String> getAvailableGroups() {
    return Sets.newHashSet(availableGroups);
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    final __old_LoggedUser other = (__old_LoggedUser) obj;
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
