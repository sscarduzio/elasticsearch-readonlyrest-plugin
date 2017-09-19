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
package org.elasticsearch.plugin.readonlyrest;

import org.elasticsearch.plugin.readonlyrest.httpclient.ApacheHttpCoreClient;
import org.elasticsearch.plugin.readonlyrest.httpclient.HttpClient;

import java.util.HashMap;

import static org.elasticsearch.plugin.readonlyrest.Constants.CACHE_WATERMARK;

public abstract class ESContext {
  private HashMap<Class<?>, LoggerShim> loggerCache = new HashMap<>(128);

  public LoggerShim logger(Class<?> clazz) {
    LoggerShim shim = loggerCache.get(clazz);
    if (shim != null) {
      return shim;
    }
    shim = mkLogger(clazz);
    if (loggerCache.size() > CACHE_WATERMARK) {
      shim.error("Possible logger cache leak, we hit the watermark of " +
                   CACHE_WATERMARK + ", now cache size="+ loggerCache.size());
    }
    loggerCache.put(clazz, shim);
    return shim;
  }

  protected abstract LoggerShim mkLogger(Class<?> clazz);

  public abstract RuntimeException rorException(String message);

  public HttpClient mkHttpClient() {
    return new ApacheHttpCoreClient(this);
  }

  public abstract ESVersion getVersion();
}
