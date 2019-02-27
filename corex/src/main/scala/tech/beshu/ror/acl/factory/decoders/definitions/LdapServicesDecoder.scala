package tech.beshu.ror.acl.factory.decoders.definitions

import io.circe.Decoder
import LdapServicesDecoder.ldapServiceDecoder
import tech.beshu.ror.acl.blocks.definitions.ldap.LdapService

class LdapServicesDecoder
  extends DefinitionsBaseDecoder[LdapService]("ldaps")(
    LdapServicesDecoder.ldapServiceDecoder
  )

object LdapServicesDecoder {
  private implicit val ldapServiceDecoder: Decoder[LdapService] = ???
}