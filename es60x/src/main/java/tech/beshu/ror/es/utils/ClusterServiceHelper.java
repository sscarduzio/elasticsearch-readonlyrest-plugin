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
package tech.beshu.ror.es.utils;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.service.ClusterService;
import tech.beshu.ror.utils.MatcherWithWildcards;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class ClusterServiceHelper {

  public static Set<String> getIndicesRelatedToTemplates(ClusterService clusterService, Set<String> templateNames) {
    Set<String> indicesPatterns = templateNames
        .stream()
        .flatMap(templateName -> getIndicesPatternsOfTemplate(clusterService, templateName).stream())
        .collect(Collectors.toSet());
    return indicesFromPatterns(clusterService, indicesPatterns).values().stream().flatMap(Collection::stream).collect(Collectors.toSet());
  }

  public static Map<String, Set<String>> indicesFromPatterns(ClusterService clusterService, Set<String> indicesPatterns) {
    Set<String> allIndices = Sets.newHashSet(clusterService.state().getMetaData().getIndices().keysIt());
    return indicesPatterns.stream().collect(
        Collectors.toMap(i -> i, i -> new MatcherWithWildcards(indicesPatterns).filter(allIndices)));
  }


  public static Set<String> getIndicesPatternsOfTemplate(ClusterService clusterService, String templateName) {
    return Optional
        .ofNullable(clusterService.state().getMetaData().templates().get(templateName))
        .map(IndexTemplateMetaData::patterns)
        .map(HashSet::new)
        .orElse(Sets.newHashSet());
  }

  public static Set<String> findTemplatesOfIndices(ClusterService clusterService, Set<String> indices) {
    Map<String, MatcherWithWildcards> templateIndexMatchers = Lists
        .newArrayList(clusterService.state().getMetaData().getTemplates().valuesIt())
        .stream()
        .collect(Collectors.toMap(IndexTemplateMetaData::getName, templateMetaData -> new MatcherWithWildcards(templateMetaData.patterns())));
    return indices.stream()
        .flatMap(index -> templateIndexMatchers.entrySet().stream().filter(t -> t.getValue().match(index)).map(Map.Entry::getKey))
        .collect(Collectors.toSet());
  }

}
