package tech.beshu.ror.utils.containers.generic

import monix.eval.Coeval
import tech.beshu.ror.utils.containers.{LdapContainer, WireMockContainer}

object dependencies {

  def ldap(name: String, ldapInitScript: String): DependencyDef = {
    DependencyDef(name = name, Coeval(new LdapContainer(name, ldapInitScript)))
  }

  def wiremock(name: String, mappings: String*): DependencyDef = {
    DependencyDef(name, containerCreator = Coeval(new WireMockScalaAdapter(WireMockContainer.create(mappings: _*))))
  }
}
