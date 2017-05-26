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
package org.elasticsearch.plugin.readonlyrest.utils.containers;

import org.testcontainers.containers.GenericContainer;

import java.util.function.Function;

public class MultiContainerDependent<T extends GenericContainer<T>>
    extends GenericContainer<MultiContainerDependent<?>> {

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
  public void start() {
    multiContainer.start();
    container = containerCreator.apply(multiContainer);
    container.start();
  }

  @Override
  public void stop() {
    container.stop();
    multiContainer.stop();
    super.stop();
  }

}
