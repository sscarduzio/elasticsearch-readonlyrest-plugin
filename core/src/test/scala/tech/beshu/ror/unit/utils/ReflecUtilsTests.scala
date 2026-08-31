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
import tech.beshu.ror.unit.utils.ReflecUtilsTests.{Child, Parent}
import tech.beshu.ror.utils.ReflecUtils

import scala.jdk.CollectionConverters.*

class ReflecUtilsTests extends AnyWordSpec {

  "ReflecUtils.fieldsNamed" should {
    "find a field of any type, so callers can write non-String values" in {
      // The BulkShardRequest path writes an ES Index object into ShardId.index. Matching on the name
      // alone is the point: a type-filtered lookup would return nothing and the index rewrite -- an
      // access-control operation -- would silently do nothing.
      val fields = ReflecUtils.fieldsNamed(classOf[Child], "payload").asScala
      fields.map(_.getType) should contain only classOf[Object]
    }
    "find fields declared in a superclass" in {
      val fields = ReflecUtils.fieldsNamed(classOf[Child], "inherited").asScala
      fields should have size 1
      fields.head.getDeclaringClass shouldBe classOf[Parent]
    }
    "return an empty set when nothing matches" in {
      ReflecUtils.fieldsNamed(classOf[Child], "nope").asScala shouldBe empty
    }
  }

  "ReflecUtils.setIndices" should {
    "write String and String[] fields, including inherited ones" in {
      val target = new Child()
      ReflecUtils.setIndices(target, Set("index", "indices", "inherited").asJava, Set("a", "b").asJava) shouldBe true
      target.index should (be("a") or be("b")) // one index is picked from the set
      target.indices.toSet shouldBe Set("a", "b")
      target.getInherited should (be("a") or be("b"))
    }
    "leave fields of other types alone" in {
      val target = new Child()
      ReflecUtils.setIndices(target, Set("payload").asJava, Set("a").asJava) shouldBe false
      target.payload shouldBe null
    }
    "do nothing when there are no new indices" in {
      val target = new Child()
      target.index = "untouched"
      ReflecUtils.setIndices(target, Set("index").asJava, Set.empty[String].asJava) shouldBe false
      target.index shouldBe "untouched"
    }
  }

}

object ReflecUtilsTests {

  // `protected`, not `private`, only to keep -Wunused:privates quiet: the field is written
  // reflectively, never from Scala. Access level is irrelevant to the traversal under test --
  // getDeclaredFields returns every level, and the caller calls setAccessible.
  class Parent {
    protected var inherited: String = "initial"
    def getInherited: String = inherited
  }

  class Child extends Parent {
    var index: String = _
    var indices: Array[String] = Array.empty
    var payload: Object = _
  }

}
