package tech.beshu.ror.es.request.queries

object ops {

  def hasWildcard(fieldName: String): Boolean = fieldName.contains("*")
}
