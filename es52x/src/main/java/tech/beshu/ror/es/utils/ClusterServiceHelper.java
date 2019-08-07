package tech.beshu.ror.es.utils;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.service.ClusterService;
import tech.beshu.ror.utils.MatcherWithWildcards;

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
    return indicesFromPatterns(clusterService, indicesPatterns);
  }

  public static Set<String> indicesFromPatterns(ClusterService clusterService, Set<String> indicesPatterns) {
    MatcherWithWildcards indicesMatcher = new MatcherWithWildcards(indicesPatterns);
    Set<String> allIndices = Sets.newHashSet(clusterService.state().getMetaData().getIndices().keysIt());
    return indicesMatcher.filter(allIndices);
  }

  public static Set<String> getIndicesPatternsOfTemplate(ClusterService clusterService, String templateName) {
    return Optional
        .ofNullable(clusterService.state().getMetaData().templates().get(templateName))
        .map(IndexTemplateMetaData::template)
        .map(Sets::newHashSet)
        .orElse(Sets.newHashSet());
  }

  public static Set<String> findTemplatesOfIndices(ClusterService clusterService, Set<String> indices) {
    Map<String, MatcherWithWildcards> templateIndexMatchers = Lists
        .newArrayList(clusterService.state().getMetaData().getTemplates().valuesIt())
        .stream()
        .collect(Collectors.toMap(IndexTemplateMetaData::getName, templateMetaData -> new MatcherWithWildcards(Sets.newHashSet(templateMetaData.template()))));
    return indices.stream()
        .flatMap(index -> templateIndexMatchers.entrySet().stream().filter(t -> t.getValue().match(index)).map(Map.Entry::getKey))
        .collect(Collectors.toSet());
  }

}
