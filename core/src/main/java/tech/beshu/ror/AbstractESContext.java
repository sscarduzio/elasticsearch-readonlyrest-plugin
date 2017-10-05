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
package tech.beshu.ror;

import tech.beshu.ror.commons.Constants;
import tech.beshu.ror.httpclient.ApacheHttpCoreClient;
import tech.beshu.ror.commons.shims.HttpClient;
import tech.beshu.ror.commons.shims.ESContext;
import tech.beshu.ror.commons.shims.ESVersion;
import tech.beshu.ror.commons.shims.LoggerShim;

import java.util.HashMap;

public abstract class AbstractESContext implements ESContext {
  private HashMap<Class<?>, LoggerShim> loggerCache = new HashMap<>(128);

  public LoggerShim logger(Class<?> clazz) {
    LoggerShim shim = loggerCache.get(clazz);
    if (shim != null) {
      return shim;
    }
    shim = mkLogger(clazz);
    if (loggerCache.size() > Constants.CACHE_WATERMARK) {
      shim.error("Possible logger cache leak, we hit the watermark of " +
                   Constants.CACHE_WATERMARK + ", now cache size="+ loggerCache.size());
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
