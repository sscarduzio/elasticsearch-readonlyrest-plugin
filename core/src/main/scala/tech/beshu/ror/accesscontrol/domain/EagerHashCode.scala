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
package tech.beshu.ror.accesscontrol.domain

/**
 * Caches a case class's `hashCode` eagerly. Mixed into small immutable domain
 * identities that appear at high frequency in `Set`/`Map` operations on the hot
 * ACL request-evaluation path, so their `hashCode` is computed once instead of
 * being recomputed on every lookup/insertion.
 *
 * The cached value is identical to the compiler-generated case class `hashCode`
 * (`MurmurHash3.productHash`), so it stays consistent with structural `equals`
 * and changes no equality semantics — only when the hash is computed.
 */
private[domain] trait EagerHashCode { this: Product =>
  override val hashCode: Int = scala.util.hashing.MurmurHash3.productHash(this)
}
