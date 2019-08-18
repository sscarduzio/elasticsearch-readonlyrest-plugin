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

import org.elasticsearch.common.inject.Singleton;
import tech.beshu.ror.boot.RorInstance;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Singleton
public class RorInstanceSupplier implements Supplier<Optional<RorInstance>> {

  private static RorInstanceSupplier instance;
  private final AtomicReference<Optional<RorInstance>> rorInstanceAtomicReference = new AtomicReference(Optional.empty());

  @Override
  public Optional<RorInstance> get() {
    return rorInstanceAtomicReference.get();
  }

  void update(RorInstance rorInstance) {
    rorInstanceAtomicReference.set(Optional.ofNullable(rorInstance));
  }

  synchronized public static RorInstanceSupplier getInstance() {
    if (instance == null) {
      instance = new RorInstanceSupplier();
    }
    return instance;
  }
}
