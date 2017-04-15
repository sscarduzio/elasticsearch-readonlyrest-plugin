package org.elasticsearch.plugin.readonlyrest.acl.requestcontext;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;

/**
 * The representation of a Transient object that has:
 * - an immutable initial value,
 * - a mutable tranient value
 * - an immutable final value
 * - a custom function that is executed when the final value is being frozen.
 * <p>
 * NB: If a Delayed.reset() is executed, the tranisent value returns to the initial value.
 * <p>
 * Created by sscarduzio on 14/04/2017.
 */

public abstract class Transactional<T> extends Delayed {

  private static Logger logger = Loggers.getLogger(Transactional.class);

  Boolean initialized = false;
  private T initialValue;
  private T transientValue;

  public Transactional(String name) {
    super(name);

    delay(() -> {
      if (!initialized) {
        lazyLoad();
      }
      if (transientValue == null && initialValue == null || transientValue.equals(initialValue)) {
        logger.info(name + " > nothing to be committed..");
        return;
      }
      logger.info(name + " > committing final value " + transientValue);
      onCommit(transientValue);
    });
  }

  private void lazyLoad() {

    initialized = true;
    initialValue = initialize();
    transientValue = copy(this.initialValue);
    logger.info(name + " > lazy loading initial value to " + initialValue);
  }

  public abstract T initialize();

  public abstract T copy(T initial);

  public abstract void onCommit(T value);

  public T get() {
    if (!initialized) {
      lazyLoad();
    }
    return transientValue;
  }

  public T getInitial() {
    if (!initialized) {
      lazyLoad();
    }
    return initialValue;
  }

  public void mutate(T newValue) {
    if (!initialized) {
      lazyLoad();
    }
    this.transientValue = newValue;
  }

  @Override
  public void reset() {
    if (!initialized) {
      lazyLoad();
    }
    super.reset();
    transientValue = initialValue;
  }
}
