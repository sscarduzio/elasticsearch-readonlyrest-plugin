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

import org.elasticsearch.ElasticsearchException;
import tech.beshu.ror.boot.StartingFailure;

public class StartingFailureException extends ElasticsearchException {
  private StartingFailureException(String msg) {
    super(msg);
  }

  private StartingFailureException(String message, Throwable throwable) {
    super(message, throwable);
  }

  public static StartingFailureException from(StartingFailure failure) {
    if(failure.throwable().isDefined()) {
      return new StartingFailureException(failure.message(), failure.throwable().get());
    } else {
      return new StartingFailureException(failure.message());
    }
  }
}
