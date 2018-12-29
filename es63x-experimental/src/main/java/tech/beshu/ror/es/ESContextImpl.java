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

package tech.beshu.ror.es;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.client.Client;
import tech.beshu.ror.commons.settings.BasicSettings;
import tech.beshu.ror.commons.shims.es.AbstractESContext;
import tech.beshu.ror.commons.shims.es.ESVersion;
import tech.beshu.ror.commons.shims.es.LoggerShim;

public class ESContextImpl extends AbstractESContext {
  private static AuditSinkImpl auditSink;
  private final BasicSettings settings;

  public ESContextImpl(Client client, BasicSettings settings) {
    if (auditSink != null) {
      auditSink.stop();
    }
    if (settings.isAuditorCollectorEnabled()) {
      auditSink = new AuditSinkImpl(client, settings);
    }
    this.settings = settings;
  }

  public static LoggerShim mkLoggerShim(Logger l) {
    return new LoggerShim() {

      @Override
      public void trace(String message) {
        l.trace(message);
      }

      @Override
      public void info(String message) {
        l.info(message);
      }

      @Override
      public void debug(String message) {
        l.debug(message);
      }

      @Override
      public void warn(String message) {
        l.warn(message);
      }

      @Override
      public void warn(String message, Throwable t) {
        l.warn(message, t);
        t.printStackTrace(System.out);
      }

      @Override
      public void error(String message, Throwable t) {
        l.error(message, t);
        t.printStackTrace(System.out);
      }

      @Override
      public void error(String message) {
        l.error(message);
      }

      @Override
      public boolean isDebugEnabled() {
        return l.isDebugEnabled();
      }
    };
  }

  @Override
  public LoggerShim mkLogger(Class<?> clazz) {
    return mkLoggerShim(LogManager.getLogger(clazz.getName()));
  }

  @Override
  public RuntimeException rorException(String message) {
    return new ElasticsearchException(message);
  }

  @Override
  public ESVersion getVersion() {
    return new ESVersion(Version.CURRENT.id);
  }

  public void submit(String indexName, String documentId, String jsonRecord) {
    auditSink.submit(indexName, documentId, jsonRecord);
  }

  @Override
  public BasicSettings getSettings() {
    return settings;
  }
}
