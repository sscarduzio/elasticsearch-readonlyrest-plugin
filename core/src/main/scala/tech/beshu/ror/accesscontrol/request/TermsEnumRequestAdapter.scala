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
package tech.beshu.ror.accesscontrol.request

// Elasticsearch's '_terms_enum' request isn't on ROR's compile classpath (it's an x-pack-core class) so each module
// adapts its own runtime-only, reflective access to 'field' to this interface. Core depends only on this interface,
// so the fields/filter rule decision logic (see TermsEnumRequestFieldsSupport) can be a common, version-agnostic impl.
trait TermsEnumRequestAdapter {
  def requestedField: Option[String]

  def modifyRequestedField(newField: String): Unit
}
