package tech.beshu.ror.unit.acl.utils

import java.util.UUID

trait UuidProvider {

  def instanceUuid: UUID
  def random: UUID
}

object JavaUuidProvider extends UuidProvider {
  override val instanceUuid: UUID = random
  override def random: UUID = UUID.randomUUID()
}