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
package tech.beshu.ror.accesscontrol.audit

import org.json.JSONObject
import tech.beshu.ror.audit.{AuditLogSerializer, AuditRequestContext, AuditResponseContext}
import tech.beshu.ror.audit.AuditResponseContext.Verbosity
import tech.beshu.ror.constants

class AclAuditLogSerializer extends AuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] = {
    val suppress = responseContext match {
      case allowed: AuditResponseContext.Allowed => allowed.verbosity != Verbosity.Info
      case _                                     => false
    }
    if (suppress) None
    else {
      val msg = formatMessage(responseContext)
      val json = new JSONObject()
      json.put(AclAuditLogSerializer.messageField, msg)
      Some(json)
    }
  }

  private def formatMessage(ctx: AuditResponseContext): String = {
    val reqStr = formatRequest(ctx.requestContext)
    ctx match {
      case ctx: AuditResponseContext.Allowed =>
        s"${constants.ANSI_CYAN}ALLOWED by ${ctx.reason} req=$reqStr${constants.ANSI_RESET}"
      case ctx: AuditResponseContext.ForbiddenBy =>
        s"${constants.ANSI_PURPLE}FORBIDDEN by ${ctx.reason} req=$reqStr${constants.ANSI_RESET}"
      case _: AuditResponseContext.Forbidden =>
        s"${constants.ANSI_PURPLE}FORBIDDEN by default req=$reqStr${constants.ANSI_RESET}"
      case _: AuditResponseContext.RequestedIndexNotExist =>
        s"${constants.ANSI_PURPLE}INDEX NOT FOUND req=$reqStr${constants.ANSI_RESET}"
      case _: AuditResponseContext.Errored =>
        s"${constants.ANSI_YELLOW}ERRORED by error req=$reqStr${constants.ANSI_RESET}"
    }
  }

  private def formatRequest(req: AuditRequestContext): String = {
    val user = req.loggedInUserName
      .getOrElse(req.attemptedUserName.map(u => s"$u (attempted)").getOrElse("[no info about user]"))
    val idx = if (req.indices.isEmpty) "<N/A>" else req.indices.toSeq.sorted.mkString(",")
    val hasBrowser = req.requestHeaders.getValue("user-agent").isDefined
    val xff = req.requestHeaders.getValue("x-forwarded-for").flatMap(_.headOption).getOrElse("null")
    val cnt = if (req.contentLength == 0) "<N/A>" else s"<OMITTED, LENGTH=${req.contentLength}>"
    s"""{ ID:${req.id}, TYP:${req.`type`}, CGR:<N/A>, USR:$user, BRS:$hasBrowser, ACT:${req.action}, OA:${req.remoteAddress}, XFF:$xff, DA:${req.localAddress}, IDX:$idx, MET:${req.httpMethod}, PTH:${req.uriPath}, CNT:$cnt, HIS:${req.history}, }"""
  }
}

object AclAuditLogSerializer {
  val messageField = "acl_message"
}
