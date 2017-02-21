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

package org.elasticsearch.plugin.readonlyrest.ldap;

import com.google.common.collect.ImmutableSet;

import java.util.Set;

public class LdapUser {
    private final String uid;
    private final String dn;
    private final ImmutableSet<LdapGroup> groups;

    public LdapUser(String uid, String dn, Set<LdapGroup> groups) {
        this.uid = uid;
        this.dn = dn;
        this.groups = ImmutableSet.copyOf(groups);
    }

    public LdapUser(String uid, String dn) {
        this(uid, dn, ImmutableSet.of());
    }

    public String getDN() {
        return dn;
    }

    public ImmutableSet<LdapGroup> getGroups() {
        return groups;
    }

    public LdapUser withGroups(Set<LdapGroup> groups) {
        return new LdapUser(uid, dn, groups);
    }
}
