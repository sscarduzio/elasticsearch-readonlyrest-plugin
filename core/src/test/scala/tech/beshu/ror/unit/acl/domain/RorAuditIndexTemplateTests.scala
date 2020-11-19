package tech.beshu.ror.unit.acl.domain

import java.time.Instant

import eu.timepit.refined.auto._
import org.scalatest.WordSpec
import org.scalatest.Matchers._
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorAuditIndexTemplate}

class RorAuditIndexTemplateTests extends WordSpec {

  // todo: do it better
  "A test" in {
    val template = RorAuditIndexTemplate.from("'.ror_'yyyy_MM").right.get
    val index = template.indexName(Instant.now())
    template.conforms(IndexName(".ror")) should be (false)
    template.conforms(IndexName("other")) should be (false)
    template.conforms(IndexName("ror*")) should be (false)
    template.conforms(IndexName(".ror_2020_01")) should be (true)
    template.conforms(IndexName("*")) should be (true)
    template.conforms(index)
    template.conforms(IndexName(".ror*")) should be (true)
  }
}
