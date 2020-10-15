package tech.beshu.ror.utils

import java.nio.charset.Charset
import java.util.Base64

import com.google.common.hash.Hashing
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

trait Hasher {
  def hashString(input: String): String
}

object Hasher {

  object Sha1 extends Hasher {
    override def hashString(input: String): String = {
      Hashing.sha1().hashString(input, Charset.defaultCharset).toString
    }
  }

  object Sha256 extends Hasher {
    override def hashString(input: String): String = {
      Hashing.sha256().hashString(input, Charset.defaultCharset).toString
    }
  }

  object Sha512 extends Hasher {
    override def hashString(input: String): String = {
      Hashing.sha512().hashString(input, Charset.defaultCharset).toString
    }
  }

  object PBKDF2WithHmacSHA512 extends Hasher {
    private val secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")

    override def hashString(input: String): String = {
      val spec = new PBEKeySpec(input.toCharArray, input.getBytes, 10000, 64 * 8 /* 512 bits */)
      val key = secretKeyFactory.generateSecret(spec)
      Base64.getEncoder.encodeToString(key.getEncoded)
    }
  }
}
