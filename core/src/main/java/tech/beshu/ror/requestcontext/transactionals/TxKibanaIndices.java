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

package tech.beshu.ror.requestcontext.transactionals;

import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.requestcontext.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public class TxKibanaIndices extends Transactional<Set<String>> {

  private final Consumer<Set<String>> committer;

  public TxKibanaIndices(ESContext context, Consumer<Set<String>> committer) {
    super("tx-kibana_indices", context);
    this.committer = committer;
  }

  @Override
  public Set<String> initialize() {
    return new HashSet<>();
  }

  @Override
  public Set<String> copy(Set<String> initial) {
    return new HashSet<>(initial);
  }

  @Override
  public void onCommit(Set<String> value) {
    committer.accept(value);
  }
}
