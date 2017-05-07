package org.elasticsearch.plugin.readonlyrest.requestcontext;

import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.domain.HttpMethod;
import org.elasticsearch.plugin.readonlyrest.acl.domain.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.domain.Verbosity;
import org.elasticsearch.plugin.readonlyrest.utils.ReflecUtils.CheckedFunction;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface RequestContext extends IndicesRequestContext {
  Optional<LoggedUser> getLoggedInUser();
  void setLoggedInUser(LoggedUser user);
  String getAction();
  String getId();
  Map<String, String> getHeaders();
  String getRemoteAddress();
  Set<String> getIndices();
  boolean involvesIndices();
  Boolean hasSubRequests();
  Integer scanSubRequests(final CheckedFunction<IndicesRequestContext, Optional<IndicesRequestContext>> replacer);
  void setResponseHeader(String name, String value);
  String getContent();
  HttpMethod getMethod();
  String getUri();
  Verbosity getVerbosity();
  void setVerbosity(Verbosity v);
  void addToHistory(Block block, Set<RuleExitResult> results);
  void reset();
  void commit();
}
