package org.elasticsearch.plugin.readonlyrest;

import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.domain.Value;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.HttpMethod;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Verbosity;
import org.elasticsearch.plugin.readonlyrest.utils.ReflecUtils;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.SubRequestContext;

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
  Integer scanSubRequests(final ReflecUtils.CheckedFunction<SubRequestContext, Optional<SubRequestContext>> replacer);
  void setResponseHeader(String name, String value);
  String getContent();
  HttpMethod getMethod();
  String getUri();
  void setVerbosity(Verbosity v);
  void addToHistory(Block block, Set<RuleExitResult> results);
}
