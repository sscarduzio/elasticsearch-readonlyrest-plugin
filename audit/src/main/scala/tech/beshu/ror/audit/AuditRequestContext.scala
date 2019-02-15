package tech.beshu.ror.audit

import java.time.Instant

trait AuditRequestContext {

  def timestamp: Instant
  def id: String
  def indices: Set[String]
  def action: String
  def headers: Map[String, String]
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
  def involvesIndices: Boolean

}