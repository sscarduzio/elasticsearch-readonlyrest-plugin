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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.reflections.Reflections;
import tech.beshu.ror.AuditLogContext;
import tech.beshu.ror.commons.ResponseContext;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;

/**
 * Created by sscarduzio on 28/06/2017.
 */
public class SerializationTool {
  private final static SimpleDateFormat zuluFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
  private final static SimpleDateFormat indexNameFormatter = new SimpleDateFormat("yyyy-MM-dd");
  private static ObjectMapper mapper;
  private static LoggerShim logger;

  static {
    zuluFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
  }

  private final ESContext esContext;
  private Optional<AuditLogSerializer> customSerializer;

  public SerializationTool(ESContext esContext) {
    this.esContext = esContext;
    this.logger = esContext.logger(getClass());
    ObjectMapper mapper = new ObjectMapper();
    SimpleModule simpleModule = new SimpleModule(
      "SimpleModule",
      new Version(1, 0, 0, null, "com.readonlyrest", "readonlyrest")
    );
    mapper.registerModule(simpleModule);
    this.mapper = mapper;

    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      findCustomSerialiser(esContext);
      return null;
    });
  }

  private void findCustomSerialiser(ESContext esContext) {
    Set<Class<? extends AuditLogSerializer>> resolvedSerialisers = new Reflections("").getSubTypesOf(AuditLogSerializer.class);
    if (resolvedSerialisers.isEmpty()) {
      logger.info("no custom audit log serialisers found, proceeding with default.");
      customSerializer = Optional.empty();
      return;
    }
    if (resolvedSerialisers.size() == 1) {
      try {
        AuditLogSerializer s = resolvedSerialisers.iterator().next().newInstance();
        customSerializer = Optional.of(s);
        return;
      } catch (InstantiationException | IllegalAccessException e) {
        logger.error("Error picking the custom serializer, proceeding with default.", e);
      }
    }
    logger.error("Error picking the custom serializer, proceeding with default.");
    customSerializer = Optional.empty();
  }

  public String mkIndexName() {
    return "readonlyrest_audit-" + indexNameFormatter.format(Calendar.getInstance().getTime());
  }

  public String toJson(ResponseContext rc) {
    RequestContext req = (RequestContext) rc.getRequestContext();

    AuditLogContext logContext = new AuditLogContext()
      .withId(req.getId())
      .withAction(req.getAction())
      .withPath(req.getUri())
      .withHeaders(req.getHeaders().keySet())
      .withAclHistory(req.getHistory().toString())
      .withContentLen(req.getContentLength())
      .withContentLenKb(req.getContentLength() == 0 ? 0 : req.getContentLength() / 1024)
      .withOrigin(req.getRemoteAddress())
      .withErrorType(rc.getError() != null ? rc.getError().getClass().getSimpleName() : null)
      .withErrorMessage(rc.getError() != null ? rc.getError().getMessage() : null)
      .withType(req.getType())
      .withTaskId(Math.toIntExact(req.getTaskId()))
      .withReqMethod(req.getMethod().name())
      .withUser(req.getLoggedInUser().isPresent() ? req.getLoggedInUser().get().getId() : null)
      .withIndices(req.involvesIndices() ? req.getIndices() : Collections.emptySet())
      .withTimestamp(zuluFormat.format(rc.getRequestContext().getTimestamp()))
      .withProcessingMillis(rc.getDurationMillis())
      .withMatchedBlock(rc.getReason())
      .withFinalState(rc.finalState().name());


    final String[] res = new String[1];

    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      try {
        if (customSerializer.isPresent()) {
          res[0] = mapper.writeValueAsString(customSerializer.get().createLoggableEntry(logContext));
        }
        else {
          res[0] = mapper.writeValueAsString(logContext);
        }
      } catch (JsonProcessingException e) {
        throw new RuntimeException("JsonProcessingException", e);
      }
      return null;
    });

    return res[0];
  }

  public String toJson(RequestContext rc) {
    try {
      return mapper.writeValueAsString(rc);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }


}
