package org.elasticsearch.plugin.readonlyrest.acl.requestcontext;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by sscarduzio on 14/04/2017.
 */
public abstract class Delayed {
  private static Logger logger = Loggers.getLogger(Delayed.class);
  protected final String name;
  private List<Runnable> effects = new LinkedList<>();
  private List<Delayed> delegates = new LinkedList<>();
  private Boolean committed = false;
  private Boolean delegated = false;
  Delayed(String name) {
    this.name = name + "-" + ((int) Math.floor(Math.random() * 101));
  }

  public void delay(Runnable r) {
    effects.add(r);
  }

  public int effectsSize() {
    return effects.size();
  }

  public int size() {
    return effects.size();
  }

  public void commit() {
    if (committed) {
      throw new RCUtils.RRContextException(name + " > already committed!");
    }
    committed = true;

    int commitSize = effects.size();
    if (commitSize == 0) {
      return;
    }
    logger.info(name + " > Committing " + effects.size() + " effects");
    Iterator<Runnable> it = effects.iterator();
    while (it.hasNext()) {
      Runnable eff = it.next();
      eff.run();
      it.remove();
    }
    delegates.forEach(d -> d.commit());
  }

  public void reset() {
    effects.clear();
    committed = false;

    delegates.forEach(d -> {
      d.effects.clear();
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
      throw new RCUtils.RRContextException(name + " > Already delegated, cannot delegate twice.");
    }

    logger.debug(name + " > delegating effects to " + ambassador.name);
    if (!this.effects.isEmpty()) {
      ambassador.effects.addAll(this.effects);
    }
    ambassador.delegates.add(this);
    delegated = true;
  }
}
