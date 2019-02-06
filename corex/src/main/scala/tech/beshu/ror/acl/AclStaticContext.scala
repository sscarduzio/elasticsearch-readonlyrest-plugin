package tech.beshu.ror.acl

import cats.data.NonEmptyList
import tech.beshu.ror.acl.blocks.Block

class AclStaticContext(blocks: NonEmptyList[Block]) {

  def involvesFilter: Boolean = false
  def doesRequirePassword: Boolean = true
}
