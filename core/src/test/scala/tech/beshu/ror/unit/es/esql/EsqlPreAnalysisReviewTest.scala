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
import tech.beshu.ror.es.esql.{EsqlPreAnalysisReview, PreAnalysisField}

import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

class EsqlPreAnalysisReviewTest extends AnyWordSpec {

  private val reviewed = Map(
    "indexes" -> PreAnalysisField.Handled,
    "enriches" -> PreAnalysisField.NotAnIndexSource,
    "linkedIndices" -> PreAnalysisField.UnsupportedIndexSource
  )

  "EsqlPreAnalysisReview.unreviewedFieldsIn" should {
    "accept a pre-analysis whose every field was reviewed" in {
      review(("indexes", nonEmptyList), ("enriches", nonEmptyList)) shouldBe None
    }
    "accept an unsupported index source the query does not use" in {
      review(("indexes", nonEmptyList), ("linkedIndices", emptyList)) shouldBe None
    }
    "reject an unsupported index source the query uses" in {
      review(("indexes", nonEmptyList), ("linkedIndices", nonEmptyList)) shouldBe Some(
        NonEmptyList.one("linkedIndices")
      )
    }
    "accept a field ES added but this query does not use" in {
      review(("indexes", nonEmptyList), ("icebergPaths", emptyList)) shouldBe None
    }
    "reject a field ES added that this query uses" in {
      review(("indexes", nonEmptyList), ("icebergPaths", nonEmptyList)) shouldBe Some(NonEmptyList.one("icebergPaths"))
    }
    "reject a field ES added that holds a value of its own" in {
      review(("indexes", nonEmptyList), ("viewPattern", Success("some-view"))) shouldBe
        Some(NonEmptyList.one("viewPattern"))
    }
    "accept an unreviewed flag, which cannot carry an index reference" in {
      review(("indexes", nonEmptyList), ("hasNewCapability", Success(java.lang.Boolean.TRUE))) shouldBe None
    }
    "accept an unreviewed field left unset" in {
      review(("indexes", nonEmptyList), ("linkedIndices", Success(null))) shouldBe None
    }
    "reject a field it cannot read, rather than assuming it is unused" in {
      review(("indexes", nonEmptyList), ("linkedIndices", Failure(new IllegalAccessException()))) shouldBe
        Some(NonEmptyList.one("linkedIndices"))
    }
    "report every field that needs a review" in {
      review(("linkedIndices", nonEmptyList), ("icebergPaths", nonEmptyList)) shouldBe
        Some(NonEmptyList.of("linkedIndices", "icebergPaths"))
    }
  }

  "EsqlPreAnalysisReview.instanceFieldNamesOf" should {
    "report the fields a superclass declares too, since an unreviewed one is what the review guards against" in {
      EsqlPreAnalysisReview.instanceFieldNamesOf(classOf[PreAnalysisSubclass]).sorted shouldBe
        List("inheritedField", "ownField")
    }
  }

  private def review(fields: (String, Try[AnyRef])*): Option[NonEmptyList[String]] = {
    EsqlPreAnalysisReview.unreviewedFieldsIn(
      fieldNames = fields.toList.map(_._1),
      reviewed = reviewed,
      valueOf = name => fields.toMap.apply(name)
    )
  }

  private def emptyList: Try[AnyRef] = Success(List.empty[String].asJava)

  private def nonEmptyList: Try[AnyRef] = Success(List("something").asJava)

}

private class PreAnalysisSuperclass(val inheritedField: java.util.List[String])

private class PreAnalysisSubclass(val ownField: java.util.List[String])
    extends PreAnalysisSuperclass(java.util.List.of())
