package tech.beshu.ror.acl.blocks.rules.utils

import tech.beshu.ror.acl.blocks.rules.utils.StringTNaturalTransformation.instances.stringIndexNameNT
import tech.beshu.ror.acl.domain.IndexName

object TemplateMatcher {

  def findTemplatesIndicesPatterns(templatesPatterns: Set[IndexName],
                                   allowedIndices: Set[IndexName]): Set[IndexName] = {
    val filteredPatterns = MatcherWithWildcardsScalaAdapter
      .create(templatesPatterns)
      .filter(allowedIndices)
    if (filteredPatterns.nonEmpty) {
      filteredPatterns
    } else {
      MatcherWithWildcardsScalaAdapter
        .create(allowedIndices)
        .filter(templatesPatterns)
    }
  }
}
