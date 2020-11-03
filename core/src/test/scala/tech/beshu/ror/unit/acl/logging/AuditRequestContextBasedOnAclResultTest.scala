package tech.beshu.ror.unit.acl.logging

import org.scalamock.scalatest.MockFactory
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{HasIndexPacks, HasIndices}
import tech.beshu.ror.accesscontrol.logging.AuditRequestContextBasedOnAclResult

class AuditRequestContextBasedOnAclResultTest extends WordSpec with MockFactory {

  import org.scalatest.Matchers._

  "find HasIndexPacks" in {
    implicitly[HasIndexPacks[BlockContext.FilterableMultiRequestBlockContext]]
    val bc: BlockContext.FilterableMultiRequestBlockContext = BlockContext.FilterableMultiRequestBlockContext(null, null, null, null, null, null)
    AuditRequestContextBasedOnAclResult.inspectIndices(bc) shouldEqual true
  }
  "find HasIndices" in {
    implicitly[HasIndices[BlockContext.SnapshotRequestBlockContext]]
    val bc: BlockContext.SnapshotRequestBlockContext = BlockContext.SnapshotRequestBlockContext(null, null, null, null, null, null, null, null)
    AuditRequestContextBasedOnAclResult.inspectIndices(bc) shouldEqual true
  }
  "not find any  indices" in {
    val bc: BlockContext.CurrentUserMetadataRequestBlockContext = BlockContext.CurrentUserMetadataRequestBlockContext(null, null, null, null)
    AuditRequestContextBasedOnAclResult.inspectIndices(bc) shouldEqual false
  }
}
