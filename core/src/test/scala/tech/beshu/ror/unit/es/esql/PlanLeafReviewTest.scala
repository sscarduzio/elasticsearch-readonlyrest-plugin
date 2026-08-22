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
package tech.beshu.ror.unit.es.esql

import cats.data.NonEmptyList
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.es.esql.PlanLeafReview
import tech.beshu.ror.es.esql.PlanLeafReview.Verdict

class PlanLeafReviewTest extends AnyWordSpec {

  private val reviewed = Map(
    "UnresolvedRelation" -> Verdict.Handled,
    "Row" -> Verdict.NotAnIndexSource,
    "UnresolvedExternalRelation" -> Verdict.UnsupportedIndexSource,
    "Explain" -> Verdict.UnsupportedIndexSource
  )

  "PlanLeafReview.unreviewedLeavesIn" should {
    "accept a plan whose every leaf was reviewed" in {
      review("UnresolvedRelation", "Row") shouldBe None
    }
    "accept a plan of leaves ROR reads the indices of" in {
      review("UnresolvedRelation", "UnresolvedRelation") shouldBe None
    }
    "reject an index source ROR cannot authorize yet" in {
      review("UnresolvedRelation", "UnresolvedExternalRelation") shouldBe
        Some(NonEmptyList.one("UnresolvedExternalRelation"))
    }
    "reject a leaf that hides a plan no walk over the plan can reach" in {
      review("Explain") shouldBe Some(NonEmptyList.one("Explain"))
    }
    "reject a leaf ES added that nobody reviewed" in {
      review("UnresolvedRelation", "IcebergRelation") shouldBe Some(NonEmptyList.one("IcebergRelation"))
    }
    "report every unreviewed leaf, each of them once" in {
      review("IcebergRelation", "Explain", "IcebergRelation") shouldBe
        Some(NonEmptyList.of("IcebergRelation", "Explain"))
    }
    "accept a plan with no leaf at all" in {
      review() shouldBe None
    }
  }

  private def review(leafTypeNames: String*): Option[NonEmptyList[String]] =
    PlanLeafReview.unreviewedLeavesIn(leafTypeNames.toList, reviewed)

}
