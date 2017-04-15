package org.elasticsearch.plugin.readonlyrest.acl.requestcontext;

import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;

import java.util.Optional;
import java.util.Set;

/**
 * Created by sscarduzio on 14/04/2017.
 */
public interface IndicesRequestContext {
  public Set<String> getAllIndicesAndAliases();

  public Set<String> getExpandedIndices();

  public Set<String> getOriginalIndices();

  public Set<String> getIndices();

  public void setIndices(Set<String> newIndices);

  public Optional<LoggedUser> getLoggedInUser();

  public void setLoggedInUser(LoggedUser user);

  public Boolean isReadRequest();
}
