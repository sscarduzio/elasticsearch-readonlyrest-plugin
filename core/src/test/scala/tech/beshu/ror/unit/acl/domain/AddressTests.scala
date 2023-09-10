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
package tech.beshu.ror.unit.acl.domain

import com.comcast.ip4s.{Cidr, Hostname, IpAddress}
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.Address

class AddressTests extends AnyWordSpec {
  import org.scalatest.matchers.should.Matchers._

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
