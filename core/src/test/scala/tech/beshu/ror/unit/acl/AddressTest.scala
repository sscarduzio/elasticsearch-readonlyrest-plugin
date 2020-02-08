package tech.beshu.ror.unit.acl

import com.comcast.ip4s.{Cidr, Hostname, IpAddress}
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.domain.Address

class AddressTest extends WordSpec {
  import org.scalatest.Matchers._

  "address" when {
    "passed expected ipv4" should {
      "be parsed" in {
        Address.from("127.0.0.1").get shouldBe ip("127.0.0.1")
      }
    }
    "passed expected hostname" should {
      "be parsed" in {
        Address.from("bambo.com").get shouldBe hostname("bambo.com")
      }
    }
    "passed expected ipv6" should {
      "be parsed" in {
        Address.from("fe80:0:0:0:90ac:ed6b:2b4e:7e5b").get shouldBe ip("fe80:0:0:0:90ac:ed6b:2b4e:7e5b")
      }
      "condensed be parsed" in {
        Address.from("fe80::90ac:ed6b:2b4e:7e5b").get shouldBe ip("fe80:0:0:0:90ac:ed6b:2b4e:7e5b")
      }
      "with scoped literal in windows be parsed" in {
        Address.from("fe80::90ac:ed6b:2b4e:7e5b%12").get shouldBe ip("fe80:0:0:0:90ac:ed6b:2b4e:7e5b")
      }
      "with scoped literal in unix be parsed" in {
        Address.from("fe80::90ac:ed6b:2b4e:7e5b%eth0").get shouldBe ip("fe80:0:0:0:90ac:ed6b:2b4e:7e5b")
      }
      "with scoped literal upper cased" in {
        Address.from("FE80::90AC:ED6B:2B4E:7E5B%eth0").get shouldBe ip("fe80:0:0:0:90ac:ed6b:2b4e:7e5b")
      }
    }
  }

  private def hostname(name:String) = {
    Address.Name(Hostname(name).get)
  }

  private def ip(ip:String) =
    IpAddress(ip).map(Cidr(_, 32)).map(Address.Ip).get

}
