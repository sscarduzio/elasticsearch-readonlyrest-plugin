package tech.beshu.ror.commons.shims.request;

import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Deprecated
public interface RequestContextShim {

  String getId();

  Set<String> getIndices();

  Date getTimestamp();

  String getAction();

  Map<String, String> getHeaders();

  String getUri();

  String getHistoryString();

  String getContent();

  Integer getContentLength();

  String getRemoteAddress();

  String getLocalAddress();

  String getType();

  Long getTaskId();

  String getMethodString();

  Optional<String> getLoggedInUserName();

  boolean involvesIndices();

}