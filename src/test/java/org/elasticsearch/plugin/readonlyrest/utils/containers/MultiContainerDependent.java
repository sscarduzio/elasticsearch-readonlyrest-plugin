package org.elasticsearch.plugin.readonlyrest.utils.containers;

import org.junit.runner.Description;
import org.testcontainers.containers.FailureDetectingExternalResource;
import org.testcontainers.containers.GenericContainer;

import java.util.function.Function;

public class MultiContainerDependent<T extends GenericContainer<T>>
    extends FailureDetectingExternalResource {

  private final MultiContainer multiContainer;
  private final Function<MultiContainer, T> containerCreator;
  private T container;

  public MultiContainerDependent(MultiContainer multiContainer,
                                 Function<MultiContainer, T> containerCreator) {
    this.multiContainer = multiContainer;
    this.containerCreator = containerCreator;
  }

  public MultiContainer getMultiContainer() {
    return multiContainer;
  }

  public T getContainer() {
    return container;
  }

  @Override
  protected void starting(Description description) {
    multiContainer.starting(description);
    container = containerCreator.apply(multiContainer);
    container.start();
  }

  @Override
  protected void finished(Description description) {
    container.stop();
    multiContainer.finished(description);
  }

}
