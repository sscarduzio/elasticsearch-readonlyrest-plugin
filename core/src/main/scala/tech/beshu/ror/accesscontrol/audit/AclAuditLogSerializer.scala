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

import cats.Show
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.domain.{Header, RorAuditLoggerName}
import tech.beshu.ror.audit.AuditResponseContext.Verbosity
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}
import tech.beshu.ror.implicits.{headerShow, obfuscatedHeaderShow}
import tech.beshu.ror.utils.RefinedUtils.nes

class AclAuditLogSerializer extends AuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] = {
    val suppress = responseContext match {
      case allowed: AuditResponseContext.Allowed => allowed.verbosity != Verbosity.Info
      case _                                     => false
    }
    if (suppress) None else Some(new JSONObject())
  }

  private[audit] def formatMessage(responseContext: AuditResponseContext, debugEnabled: Boolean): String = {
    responseContext.requestContext match {
      case ctx: AuditRequestContextBasedOnAclResult[?] =>
        given Show[Header] =
          if (debugEnabled) headerShow else obfuscatedHeaderShow(ctx.loggingContext.obfuscatedHeaders)
        ctx.aclMessageShow(debugEnabled).show(ctx.responseContext)
      case ctx =>
        // AuditRequestContext is an open trait in the published audit module, so a third-party
        // implementation could reach here. Throwing would surface as silent "Auditing issue" noise
        // at the Task boundary; instead emit a minimal but identifiable line from the base-trait fields.
        s"[unknown-context:${ctx.getClass.getSimpleName}] id=${ctx.id} action=${ctx.action} uri=${ctx.uriPath}"
    }
  }

}

object AclAuditLogSerializer {
  // Preserved from the class that originally emitted ACL log entries, so existing
  // log4j configurations targeting that logger name continue to work unchanged.
  val defaultLoggerName: RorAuditLoggerName =
    RorAuditLoggerName(nes("tech.beshu.ror.accesscontrol.logging.AccessControlListLoggingDecorator"))
}
