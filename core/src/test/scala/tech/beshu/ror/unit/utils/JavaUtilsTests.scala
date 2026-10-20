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
package tech.beshu.ror.unit.utils

import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.utils.JavaUtils.Sets

import scala.jdk.CollectionConverters.*

class JavaUtilsTests extends AnyWordSpec {

  "Sets.newHashSet" should {
    "create a set from varargs" in {
      Sets.newHashSet("a", "b", "a").asScala should be(Set("a", "b"))
    }
    "create an empty set" in {
      Sets.newHashSet[String]().asScala should be(Set.empty)
    }
    "create a mutable copy of an iterable" in {
      val source = java.util.List.of("a", "b")
      val copy = Sets.newHashSet(source)
      copy.add("c")
      copy.asScala should be(Set("a", "b", "c"))
      source.size should be(2)
    }
    "create a set from an iterable which is not a collection" in {
      val source = new java.lang.Iterable[String] {
        override def iterator(): java.util.Iterator[String] = java.util.List.of("a", "b", "a").iterator()
      }
      Sets.newHashSet(source).asScala should be(Set("a", "b"))
    }
  }

}
