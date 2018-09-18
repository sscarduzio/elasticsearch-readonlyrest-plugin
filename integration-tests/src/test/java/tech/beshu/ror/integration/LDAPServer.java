package tech.beshu.ror.integration;

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

import tech.beshu.ror.utils.containers.LdapContainer;

/**
 * This is really useful when you want to stand up a LDAP server for manual tests
 */
public class LDAPServer {
  public static void main(String[] args) throws InterruptedException {
    String ldifFile = System.getProperty("config", "/ldap_integration_group_headers/ldap.ldif");
    System.out.println(LDAPServer.class.getSimpleName() + " using config file: " + ldifFile);
    LdapContainer lc = LdapContainer.create(ldifFile);
    lc.start();
    System.out.println(lc.getLdapHost() + " " + lc.getLdapPort());
    while(true){
      Thread.sleep(Long.MAX_VALUE);
    }
  }
}
