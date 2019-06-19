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
package tech.beshu.ror.utils.assertions;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

public class EnhancedAssertion {

  public static void assertNAttempts(Integer n, Callable<Void> action) {
    RetryPolicy<Void> retryPolicy = new RetryPolicy<Void>()
        .handleIf(assertionFails())
        .withMaxRetries(n)
        .withDelay(Duration.ofMillis(200));
    Failsafe.with(retryPolicy).get(action::call);
  }

  private static Predicate<Throwable> assertionFails() {
    return throwable -> throwable instanceof AssertionError;
  }
}
