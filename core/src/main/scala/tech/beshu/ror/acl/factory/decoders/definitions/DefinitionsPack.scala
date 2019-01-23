package tech.beshu.ror.acl.factory.decoders.definitions

import cats.Show
import tech.beshu.ror.acl.blocks.definitions._

import scala.language.higherKinds

final case class DefinitionsPack(proxies: Definitions[ProxyAuth],
                                 users: Definitions[UserDef],
                                 authenticationServices: Definitions[ExternalAuthenticationService])

final case class Definitions[Item](items: Set[Item]) extends AnyVal
object Definitions {
  trait Item {
    type Id
    def id: Id
    implicit def show: Show[Id]
  }

}