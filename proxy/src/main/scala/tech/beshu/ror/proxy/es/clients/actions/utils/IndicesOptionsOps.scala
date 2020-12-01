/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions.utils

import org.elasticsearch.action.support.IndicesOptions
import org.elasticsearch.action.support.IndicesOptions.WildcardStates

import scala.collection.JavaConverters._
import scala.language.implicitConversions

class IndicesOptionsOps(val indicesOptions: IndicesOptions) extends AnyVal {

  def toQueryParams: Map[String, String] = Map(
    ignoreUnavailable,
    allowNoIndices,
    expandWildcards,
    ignoreThrottled
  )

  private def ignoreUnavailable =
    ("ignore_unavailable", indicesOptions.ignoreUnavailable().toString)

  private def allowNoIndices =
    ("allow_no_indices", indicesOptions.allowNoIndices().toString)

  private def expandWildcards = {
    val states = indicesOptions.getExpandWildcards.asScala
    val statesString =
      if (states.isEmpty)
        "none"
      else
        states
          .map {
            case WildcardStates.OPEN => "open"
            case WildcardStates.CLOSED => "closed"
            case WildcardStates.HIDDEN => "hidden"
          }
          .mkString(",")
    ("expand_wildcards", statesString)
  }

  private def ignoreThrottled =
    ("ignore_throttled", indicesOptions.ignoreThrottled().toString)
}

object IndicesOptionsOps {
  implicit def toIndicesOptionsOps(indicesOptions: IndicesOptions): IndicesOptionsOps =
    new IndicesOptionsOps(indicesOptions)
}