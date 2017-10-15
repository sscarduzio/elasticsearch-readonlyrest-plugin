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

import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by sscarduzio on 14/04/2017.
 */
public abstract class Delayed {
  protected final String name;
  private final LoggerShim logger;
  private final ESContext context;
  private final List<Runnable> effects = new LinkedList<>();
  private final List<Delayed> delegates = new LinkedList<>();
  private Boolean committed = false;
  private Boolean delegated = false;

  public Delayed(String name, ESContext context) {
    this.logger = context.logger(getClass());
    this.name = name;
    this.context = context;
  }

  public void delay(Runnable r) {
    effects.add(r);
  }

  public int size() {
    return effects.size();
  }

  public void commit() {
    if (committed) {
      throw context.rorException(name + " > already committed!");
    }
    committed = true;

    logger.trace(name + " > Committing " + effects.size() + " effects");
    Iterator<Runnable> it = effects.iterator();
    while (it.hasNext()) {
      Runnable eff = it.next();
      try {
        eff.run();
      } catch (Throwable t) {
        t.printStackTrace();
      } finally {
        logger.trace(name + " > committed.");
      }
      it.remove();
    }
    delegates.forEach(Delayed::commit);
  }

  public void reset() {
    logger.trace(name + " > resetting!!! ");
    effects.clear();
    committed = false;

    delegates.forEach(d -> {
      d.reset();
      d.committed = false;
    });
  }

  /**
   * Replace this effects queue with the ambassador's queue
   * That is, when the ambassador commits/resets, also this instance's effects are committed/reset.
   *
   * @param ambassador the Delayed instance we are delegating our commit/reset to.
   */

  public void delegateTo(Delayed ambassador) {
    if (delegated) {
      throw context.rorException(name + " > Already delegated, cannot delegate twice.");
    }

    logger.trace(name + " > delegating effects to " + ambassador.name);
    if (!this.effects.isEmpty()) {
      ambassador.effects.addAll(this.effects);
    }
    ambassador.delegates.add(this);
    delegated = true;
  }
}
