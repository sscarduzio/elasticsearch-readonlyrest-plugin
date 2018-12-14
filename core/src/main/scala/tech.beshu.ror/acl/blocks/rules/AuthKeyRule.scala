package tech.beshu.ror.acl.blocks.rules

import com.typesafe.scalalogging.StrictLogging
import tech.beshu.ror.acl.utils.BasicAuthOps._
import tech.beshu.ror.acl.utils.TryOps._
import tech.beshu.ror.utils.BasicAuthUtils

class AuthKeyRule(settings: BasicAuthenticationRule.Settings)
  extends BasicAuthenticationRule(settings)
    with StrictLogging {

  override protected def authenticate(configuredAuthKey: String,
                                      basicAuth: BasicAuthUtils.BasicAuth): Boolean = {
    basicAuth
      .tryDecode
      .map(_ == configuredAuthKey)
      .getOr { ex =>
        logger.warn("Exception while authentication", ex)
        false
      }
  }
}
