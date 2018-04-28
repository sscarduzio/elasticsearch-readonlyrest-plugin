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

package tech.beshu.ror.commons.domain;

import com.google.common.collect.Sets;
import tech.beshu.ror.commons.Constants;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class LoggedUser {

  private final String id;
  private final Set<String> availableGroups = Sets.newHashSet();
  private Optional<String> currentGroup = Optional.empty();

  public LoggedUser(String id) {
    this.id = id;
  }

  public String getId() {
    return id;
  }

  public void addAvailableGroups(Set<String> groups) {
    availableGroups.addAll(groups);
  }

  public Optional<String> resolveCurrentGroup(Map<String,String> requestHeaders) {
    if (currentGroup.isPresent()){
      return currentGroup;
    }
    Optional<String> value =  Optional.ofNullable(requestHeaders.get(Constants.HEADER_GROUP_CURRENT));
    if(!value.isPresent() && !availableGroups.isEmpty()){
      value = Optional.of(availableGroups.iterator().next());
    }
    currentGroup = value;
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
