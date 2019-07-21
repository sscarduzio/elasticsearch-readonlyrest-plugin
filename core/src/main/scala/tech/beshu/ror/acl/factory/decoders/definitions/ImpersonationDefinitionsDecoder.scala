package tech.beshu.ror.acl.factory.decoders.definitions

import cats.Id
import tech.beshu.ror.acl.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.acl.utils.ADecoder

object ImpersonationDefinitionsDecoder {

  def instance(): ADecoder[Id, Definitions[ImpersonatorDef]] = ???
}
