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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.testcontainers.containers.FailureDetectingExternalResource;
import org.testcontainers.containers.GenericContainer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class MultiContainer extends FailureDetectingExternalResource {

  private ImmutableMap<String, NamedContainer> containers;

  public MultiContainer(Map<String, Supplier<GenericContainer<?>>> containerCreators) {
    List<NamedContainer> namedContainers = containerCreators.entrySet().stream()
      .map(entry -> new NamedContainer(entry.getKey(), entry.getValue().get()))
      .collect(Collectors.toList());
    this.containers = Maps.uniqueIndex(namedContainers, NamedContainer::getName);
    namedContainers.forEach(c -> c.getContainer().start());
  }

  public <T extends GenericContainer<?>> T get(String name, Class<T> clazz) {
    return clazz.cast(containers.get(name).getContainer());
  }

  public ImmutableList<NamedContainer> containers() {
    return containers.values().asList();
  }

  public static class Builder {
    private final Map<String, Supplier<GenericContainer<?>>> containers = new HashMap<>();

    public Builder add(String name, Supplier<GenericContainer<?>> containerCreator) {
      containers.put(name, containerCreator);
      return this;
    }

    public MultiContainer build() {
      return new MultiContainer(containers);
    }
  }

  public static class NamedContainer {
    private final String name;
    private final GenericContainer<?> container;

    private NamedContainer(String name, GenericContainer<?> container) {
      this.name = name;
      this.container = container;
    }

    public String getName() {
      return name;
    }

    public GenericContainer<?> getContainer() {
      return container;
    }

    public Optional<String> getIpAddress() {
      return Optional.ofNullable(container.getContainerInfo())
        .map(info -> info
          .getNetworkSettings()
          .getNetworks()
          .get("bridge")
          .getIpAddress());
    }
  }
}
