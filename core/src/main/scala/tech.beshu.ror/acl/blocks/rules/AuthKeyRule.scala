package tech.beshu.ror.acl.blocks.rules

import java.util.regex.{Matcher, Pattern}

import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.codec.digest.Crypt.crypt
import tech.beshu.ror.utils.BasicAuthUtils
import tech.beshu.ror.acl.utils.BasicAuthOps._
import tech.beshu.ror.acl.utils.TryOps._

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
