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
package tech.beshu.ror.es.esql

import cats.data.NonEmptyList

sealed trait PlanLeaf

object PlanLeaf {

  /** ROR extracts the index references this node carries. */
  case object Handled extends PlanLeaf

  /** Reviewed and confirmed to carry no index reference the `indices` rule could authorize. */
  case object NotAnIndexSource extends PlanLeaf

  /** Carries index references ROR cannot authorize yet, so a query that uses it has to be rejected. */
  case object UnsupportedIndexSource extends PlanLeaf
}

/**
 * A query reads its indices through the leaves of its parsed plan, and ES has grown a new kind of leaf per source
 * of data it added ([[PlanLeaf.Handled]] `UnresolvedRelation` for `FROM`, `TS`, `LOOKUP JOIN` and `PROMQL`,
 * `UnresolvedExternalRelation` for Iceberg tables). A leaf nobody reviewed is indistinguishable from an absent
 * one - both leave ROR extracting nothing, the query text holding nothing to replace, and the two agreeing that
 * there is nothing to do - so the indices it holds reach ES unauthorized. Reviewing the whole leaf set turns the
 * next such addition into a rejection.
 *
 * A leaf may also hide a plan rather than an index: `Explain` holds the query it explains, which no walk over the
 * plan can reach, because a leaf reports no children. Reviewing it as [[PlanLeaf.UnsupportedIndexSource]] is what
 * keeps such a query from passing for one that reads nothing.
 */
object EsqlPlanLeafReview {

  def unreviewedLeavesIn(
      leafTypeNames: List[String],
      reviewed: Map[String, PlanLeaf]
  ): Option[NonEmptyList[String]] = {
    NonEmptyList.fromList {
      leafTypeNames.distinct.filter { typeName =>
        reviewed.get(typeName) match {
          case Some(PlanLeaf.Handled) | Some(PlanLeaf.NotAnIndexSource) => false
          case Some(PlanLeaf.UnsupportedIndexSource) | None             => true
        }
      }
    }
  }

}
