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
import cz.seznam.euphoria.shaded.guava.com.google.common.base.Strings;
import tech.beshu.ror.commons.ResponseContext;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Optional;

/**
 * Created by sscarduzio on 28/06/2017.
 */
public class SerializationTool {
  private final static SimpleDateFormat indexNameFormatter = new SimpleDateFormat("yyyy-MM-dd");
  private static ObjectMapper mapper;
  private final AuditLogSerializer auditLogSerializer;
  private final LoggerShim logger;

  public SerializationTool(ESContext esContext) {
    this.logger = esContext.logger(getClass());
    ObjectMapper mapper = new ObjectMapper();
    SimpleModule simpleModule = new SimpleModule(
      "SimpleModule",
      new Version(1, 0, 0, null, "com.readonlyrest", "readonlyrest")
    );
    mapper.registerModule(simpleModule);
    this.mapper = mapper;

    final AuditLogSerializer[] als = new AuditLogSerializer[1];
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      Optional<AuditLogSerializer> custSer = findCustomSerialiser(esContext);
      als[0] = custSer.orElse(new DefaultAuditLogSerializer());
      return null;
    });
    auditLogSerializer = als[0];
  }

  private Optional<AuditLogSerializer> findCustomSerialiser(ESContext esContext) {
    Optional<String> configuredSerializer = esContext.getSettings().getCustomAuditSerializer().filter(s -> !Strings.isNullOrEmpty(s));

    if (!configuredSerializer.isPresent()) {
      logger.info("no custom audit log serialisers found, proceeding with default.");
      return Optional.empty();
    }

    try {
      Class clazz = Class.forName(configuredSerializer.get());
      Constructor constr = clazz.getConstructor(new Class[0]);
      constr.setAccessible(true);
      AuditLogSerializer serializerInstance = (AuditLogSerializer) constr.newInstance(new Object[0]);
      logger.info("Using custom serializer: " + serializerInstance.getClass().getName());
      return Optional.of(serializerInstance);

    } catch (InstantiationException | IllegalAccessException | ClassNotFoundException | NoSuchMethodException | InvocationTargetException e) {
      logger.error("Error picking the custom serializer, proceeding with default.", e);
      return Optional.empty();
    }
  }

  public String mkIndexName() {
    return "readonlyrest_audit-" + indexNameFormatter.format(Calendar.getInstance().getTime());
  }

  public String toJson(ResponseContext rc) {
    final String[] res = new String[1];

    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      try {
        res[0] = mapper.writeValueAsString(auditLogSerializer.createLoggableEntry(rc));
      } catch (JsonProcessingException e) {
        throw new RuntimeException("JsonProcessingException", e);
      }
      return null;
    });

    return res[0];
  }


}
