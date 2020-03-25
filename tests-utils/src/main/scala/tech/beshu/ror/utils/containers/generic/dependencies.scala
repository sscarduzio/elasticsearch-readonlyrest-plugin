package tech.beshu.ror.utils.containers.generic

import monix.eval.Coeval
import tech.beshu.ror.utils.containers.WireMockContainer

object dependencies {

  def ldap(name: String, ldapInitScript: String): DependencyDef = {
    DependencyDef(
      name = name,
      Coeval(LdapContainer.create(name, ldapInitScript)),
      originalPort = LdapContainer.defaults.ldap.port)
  }

  def wiremock(name: String, mappings: String*): DependencyDef = {
    DependencyDef(name,
      containerCreator = Coeval(new WireMockScalaAdapter(WireMockContainer.create(mappings: _*))),
      originalPort = WireMockContainer.WIRE_MOCK_PORT)
  }
}
