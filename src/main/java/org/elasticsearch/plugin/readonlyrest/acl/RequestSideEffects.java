/*
 * This file is part of ReadonlyREST.
 *
 *     ReadonlyREST is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ReadonlyREST is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with ReadonlyREST.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.elasticsearch.plugin.readonlyrest.acl;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;

import java.util.LinkedList;
import java.util.List;

/**
 * Created by sscarduzio on 19/01/2017.
 */
public class RequestSideEffects {
  private final ESLogger logger = Loggers.getLogger(getClass());

  RequestContext rc;

  RequestSideEffects(RequestContext rc) {
    this.rc = rc;
  }

  List<Runnable> effects = new LinkedList<>();

  void appendEffect(Runnable eff) {
    effects.add(eff);
  }

  int size() {
    return effects.size();
  }

  void commit() {
    int commitSize = effects.size();
    if (commitSize == 0) {
      return;
    }
    logger.info("Committing " + effects.size() + " effects");
    for (Runnable eff : effects) {
      eff.run();
      effects.remove(eff);
    }
  }

  void clear() {
    effects.clear();
  }

}
