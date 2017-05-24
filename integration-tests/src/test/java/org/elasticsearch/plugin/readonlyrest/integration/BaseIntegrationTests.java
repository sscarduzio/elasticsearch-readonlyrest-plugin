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
package org.elasticsearch.plugin.readonlyrest.integration;

import com.google.common.collect.Lists;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.elasticsearch.plugin.readonlyrest.utils.gradle.RorPluginGradleProject.getRootProject;

@RunWith(Parameterized.class)
public abstract class BaseIntegrationTests {

  @Parameterized.Parameters(name = "{0}")
  public static Collection<String> esSpecificVersions() {
    return Optional.ofNullable(getRootProject().listFiles())
        .map(Lists::newArrayList)
        .orElse(Lists.newArrayList())
        .stream()
        .filter(File::isDirectory)
        .map(File::getName)
        .filter(s -> s.startsWith("es"))
        .collect(Collectors.toList());
  }

}
