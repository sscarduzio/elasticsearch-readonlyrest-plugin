package org.elasticsearch.plugin.readonlyrest.requestcontext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.collect.Maps;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * Created by sscarduzio on 28/06/2017.
 */
public class SerializationTool {
  private final static SimpleDateFormat zuluFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

  static {
    zuluFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
  }

  private final ObjectMapper mapper;

  public SerializationTool() {
    ObjectMapper mapper = new ObjectMapper();
    SimpleModule simpleModule = new SimpleModule(
      "SimpleModule",
      new Version(1, 0, 0, null)
    );
    mapper.registerModule(simpleModule);
    this.mapper = mapper;
  }

  public String toJson(RequestContext rc) throws JsonProcessingException {
    return mapper.writeValueAsString(rc);
  }

  public String toJson(ResponseContext rc) throws JsonProcessingException {

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("match", rc.getResult() != null ? rc.getResult().isMatch() : rc.getResult());
    result.put("block", (rc.getResult() != null && rc.getResult().isMatch()) ? rc.getResult().getBlock().getName() : null);

    Map<String, Object> map = Maps.newHashMap();

    map.put("id", rc.getRequestContext().getId());
    map.put("final_state", rc.finalState().name());

    map.put("@timestamp", zuluFormat.format(rc.getRequestContext().getTimestamp()));
    map.put("processingMillis", rc.getDurationMillis());

    map.put("error_type", rc.getError() != null ? rc.getError().getClass().getSimpleName() : null);
    map.put("error_message", rc.getError() != null ? rc.getError().getMessage() : null);
    map.put("matched_block", rc.getResult() != null && rc.getResult().getBlock() != null ? rc.getResult().getBlock().getName() : null);

    RequestContext req = rc.getRequestContext();

    map.put("content_len", req.getContent() == null ? 0 : req.getContent().length());
    map.put("content_len_kb", req.getContent() == null ? 0 : req.getContent().length() / 1024);
    map.put("type", req.getType());
    map.put("origin", req.getRemoteAddress());
    map.put("task_id", req.getTaskId());

    map.put("req_method", req.getMethod().name());
    map.put("headers", req.getHeaders().keySet());
    map.put("path", req.getUri());

    map.put("user", req.getLoggedInUser().isPresent() ? req.getLoggedInUser().get().getId() : null);

    map.put("action", req.getAction());
    map.put("indices", req.involvesIndices() ? req.getIndices() : Collections.emptySet());
    map.put("acl_history", req.getHistory().toString());

    return mapper.writeValueAsString(map);
  }

}
