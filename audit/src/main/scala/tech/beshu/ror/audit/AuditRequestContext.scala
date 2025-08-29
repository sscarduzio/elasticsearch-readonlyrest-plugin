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
package tech.beshu.ror.audit

import java.time.Instant

import org.json.JSONObject

trait AuditRequestContext {
  def timestamp: Instant
  def id: String
  def correlationId: String
  def indices: Set[String]
  def action: String
  @deprecated("Use requestHeaders instead", "1.22.0")
  def headers: Map[String, String]
  def requestHeaders: Headers
  def uriPath: String
  def history: String
  def content: String
  def contentLength: Integer
  def remoteAddress: String
  def localAddress: String
  def `type`: String
  def taskId: Long
  def httpMethod: String
  def loggedInUserName: Option[String]
  def impersonatedByUserName: Option[String]
  def involvesIndices: Boolean
  def attemptedUserName: Option[String]
  def rawAuthHeader: Option[String]
  def generalAuditEvents: JSONObject
  def auditEnvironmentContext: AuditEnvironmentContext
}