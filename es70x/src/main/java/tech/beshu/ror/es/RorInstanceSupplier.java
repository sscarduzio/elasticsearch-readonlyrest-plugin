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
