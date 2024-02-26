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
package tech.beshu.ror.unit.acl.blocks

import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{HasIndexPacks, HasIndices}

class BlockContextContainsIndicesTest extends AnyWordSpec {

  import org.scalatest.matchers.should.Matchers._

  "find HasIndexPacks" in {
    implicitly[HasIndexPacks[BlockContext.FilterableMultiRequestBlockContext]]
    val bc: BlockContext.FilterableMultiRequestBlockContext = BlockContext.FilterableMultiRequestBlockContext(null, null, null, null, null, null)
    bc.involvesIndices shouldEqual true
  }
  "find HasIndices" in {
    implicitly[HasIndices[BlockContext.SnapshotRequestBlockContext]]
    val bc: BlockContext.SnapshotRequestBlockContext = BlockContext.SnapshotRequestBlockContext(null, null, null, null, null, null, null, null)
    bc.involvesIndices shouldEqual true
  }
  "not find any  indices" in {
    val bc: BlockContext.CurrentUserMetadataRequestBlockContext = BlockContext.CurrentUserMetadataRequestBlockContext(null, null, null, null)
    bc.involvesIndices shouldEqual false
  }
}
