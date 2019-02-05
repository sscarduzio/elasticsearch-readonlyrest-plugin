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
package tech.beshu.ror.requestcontext;

import com.google.common.base.Strings;
import cz.seznam.euphoria.shaded.guava.com.google.common.collect.Maps;
import tech.beshu.ror.commons.ResponseContext;
import tech.beshu.ror.commons.shims.request.RequestContextShim;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Map;
import java.util.TimeZone;

public class DefaultAuditLogSerializer implements AuditLogSerializer<Map<String, ?>> {
  private final static SimpleDateFormat zuluFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

  static {
    zuluFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
  }

  @Override
  public Map<String, ?> createLoggableEntry(ResponseContext rc) {
    Map<String, Object> map = Maps.newHashMap();

    map.put("match", rc.getIsMatch());
    map.put("block", rc.getReason());

    map.put("id", rc.getRequestContext().getId());
    map.put("final_state", rc.finalState().name());

    map.put("@timestamp", zuluFormat.format(rc.getRequestContext().getTimestamp()));
    map.put("processingMillis", rc.getDurationMillis());

    map.put("error_type", rc.getError() != null ? rc.getError().getClass().getSimpleName() : null);
    map.put("error_message", rc.getError() != null ? rc.getError().getMessage() : null);
    //map.put("matched_block", rc.getResult() != null && rc.getResult().getBlock() != null ? rc.getResult().getBlock().getName() : null);

    RequestContextShim req = rc.getRequestContext();

    map.put("content_len", req.getContentLength());
    map.put("content_len_kb", req.getContentLength() / 1024);
    map.put("type", req.getType());
    map.put("origin", req.getRemoteAddress());
    map.put("destination", req.getLocalAddress());

    String xff = req.getHeaders().get("X-Forwarded-For");
    if(!Strings.isNullOrEmpty(xff)){
      map.put("xff", xff);
    }

    map.put("task_id", req.getTaskId());

    map.put("req_method", req.getMethodString());
    map.put("headers", req.getHeaders().keySet());
    map.put("path", req.getUri());

    map.put("user", req.getLoggedInUserName().orElse(null));

    map.put("action", req.getAction());
    map.put("indices", req.involvesIndices() ? req.getIndices() : Collections.emptySet());
    map.put("acl_history", req.getHistoryString());
    return map;
  }
}
