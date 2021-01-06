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
package tech.beshu.ror.integration.suites

import cats.data.NonEmptyList
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.base.BaseTemplatesSuite
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.EsContainerCreator
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.TemplateManager

trait IndexTemplatesManagementSuite
  extends WordSpec
    with BaseTemplatesSuite
    with ESVersionSupport {
  this: EsContainerCreator =>

  import IndexTemplatesManagementSuite.examples._

  override implicit val rorConfigFileName = "/templates/readonlyrest.yml"

  private lazy val dev1TemplateManager = new TemplateManager(basicAuthClient("dev1", "test"))
  private lazy val dev2TemplateManager = new TemplateManager(basicAuthClient("dev2", "test"))

  "An old template API" when {
    "user is dev1" should {
      "see empty list of templates" when {
        "there is none" in {
          val templates = dev1TemplateManager.getTemplates

          templates.responseCode should be(200)
          templates.responseJson.obj.size should be(0)
        }
      }
      "be allowed to get all templates" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val templates = dev1TemplateManager.getTemplates

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val templates = dev1TemplateManager.getTemplates

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val templates = dev1TemplateManager.getTemplates

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val templates = dev1TemplateManager.getTemplates

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val templates = dev1TemplateManager.getTemplates

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val templates = dev1TemplateManager.getTemplates

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val templates = dev1TemplateManager.getTemplates

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val templates = dev1TemplateManager.getTemplates

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
          }
        }
      }
      "be allowed to get specific template" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
          }
        }
        "at least one template index pattern matches user's allowed indices" excludeES (allEs5x) in {
          adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*", "custom_dev2_*"))
          createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")
          createIndexWithExampleDoc(adminDocumentManager, "custom_dev2_index_test")

          val templates = dev1TemplateManager.getTemplate("temp1")

          templates.responseCode should be(200)
          templates.responseJson.obj.keys.toList should contain only "temp1"
        }
      }
      "be allowed to create new template" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              val result = dev1TemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_*"))

              result.responseCode should be(200)
            }
            "rule has index pattern with no wildcard" in {
              val result = dev1TemplateManager.insertTemplate("new_template", templateExample("dev1_index*"))

              result.responseCode should be(200)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              val result = dev1TemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_test"))

              result.responseCode should be(200)
            }
            "rule has index pattern with no wildcard" in {
              val result = dev1TemplateManager.insertTemplate("new_template", templateExample("dev1_index"))

              result.responseCode should be(200)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminDocumentManager.createFirstDoc("custom_dev1_index_test", ujson.read("""{"hello":"world"}"""))

              val result = dev1TemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_*"))

              result.responseCode should be(200)
            }
            "rule has index pattern with no wildcard" in {
              adminDocumentManager.createFirstDoc("dev1_index", ujson.read("""{"hello":"world"}"""))

              val result = dev1TemplateManager.insertTemplate("new_template", templateExample("dev1_index*"))

              result.responseCode should be(200)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminDocumentManager.createFirstDoc("custom_dev1_index_test", ujson.read("""{"hello":"world"}"""))

              val result = dev1TemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_test"))

              result.responseCode should be(200)
            }
            "rule has index pattern with no wildcard" in {
              adminDocumentManager.createFirstDoc("dev1_index", ujson.read("""{"hello":"world"}"""))

              val result = dev1TemplateManager.insertTemplate("new_template", templateExample("dev1_index"))

              result.responseCode should be(200)
            }
          }
        }
        "template applies to generic index pattern (ES >= 6.0.0)" excludeES (allEs5x) in {
          val result = dev1TemplateManager.insertTemplate("new_template", templateExample("custom_*"))

          result.responseCode should be(200)

          val user1Template = adminTemplateManager.getTemplate("new_template")
          user1Template.responseCode should be(200)
          user1Template.responseJson.obj("new_template").obj("index_patterns").arr.map(_.str).toList should be(
            "custom_dev1_index_*" :: Nil
          )
        }
        "template applies to generic index pattern (ES < 6.0.0)" excludeES(allEs6x, allEs7x, rorProxy) in {
          val result = dev1TemplateManager.insertTemplate("new_template", templateExample("custom_*"))

          result.responseCode should be(200)

          val user1Template = adminTemplateManager.getTemplate("new_template")
          user1Template.responseCode should be(200)
          user1Template.responseJson.obj("new_template").obj("template").str should be("custom_dev1_index_*")
        }
      }
      "be allowed to override existing template" which {
        "belongs to him (ES >= 6.0.0)" excludeES (allEs5x) in {
          val dev1TemplateManager = new TemplateManager(basicAuthClient("dev1", "test"))

          val insert1Result =
            dev1TemplateManager.insertTemplate("new_template", templateExample("custom_dev1*"))
          insert1Result.responseCode should be(200)

          val user1Template = dev1TemplateManager.getTemplates
          user1Template.responseCode should be(200)
          user1Template.responseJson.obj("new_template").obj("index_patterns").arr.map(_.str).toList should be(
            "custom_dev1_index_*" :: Nil
          )

          val insert2Result =
            dev1TemplateManager.insertTemplate("new_template", templateExample("dev1_index*"))
          insert2Result.responseCode should be(200)

          val user1TemplateAfterOverride = dev1TemplateManager.getTemplates
          user1TemplateAfterOverride.responseCode should be(200)
          user1TemplateAfterOverride.responseJson.obj("new_template").obj("index_patterns").arr.map(_.str).toList should be(
            "dev1_index" :: Nil
          )
        }
        "belongs to him (ES < 6.0.0)" excludeES(allEs6x, allEs7x, rorProxy) in {
          val dev1TemplateManager = new TemplateManager(basicAuthClient("dev1", "test"))

          val insert1Result =
            dev1TemplateManager.insertTemplate("new_template", templateExample("custom_dev1*"))
          insert1Result.responseCode should be(200)

          val user1Template = dev1TemplateManager.getTemplates
          user1Template.responseCode should be(200)
          user1Template.responseJson.obj("new_template").obj("template").str should be("custom_dev1_index_*")

          val insert2Result =
            dev1TemplateManager.insertTemplate("new_template", templateExample("dev1_index*"))
          insert2Result.responseCode should be(200)

          val user1TemplateAfterOverride = dev1TemplateManager.getTemplates
          user1TemplateAfterOverride.responseCode should be(200)
          user1TemplateAfterOverride.responseJson.obj("new_template").obj("template").str should be("dev1_index")
        }
      }
      "be allowed to remove his template" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("custom_dev1_index_*"))

              val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

              result.responseCode should be(200)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("dev1_index"))

              val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

              result.responseCode should be(200)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("custom_dev1_index_test"))

              val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

              result.responseCode should be(200)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("dev1_index"))

              val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

              result.responseCode should be(200)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("custom_dev1_index_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

              result.responseCode should be(200)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

              result.responseCode should be(200)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

              result.responseCode should be(200)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

              result.responseCode should be(200)
            }
          }
        }
        "all indices patterns defined in template are allowed to the user" excludeES (allEs5x) in {
          adminTemplateManager.insertTemplateAndWaitForIndexing("temp", templateExample("custom_dev1_index_*", "dev1_index"))

          val result = dev1TemplateManager.deleteTemplate("temp")

          result.responseCode should be(200)
        }
        "index pattern defined in template is allowed to the user" in {
          adminTemplateManager.insertTemplateAndWaitForIndexing("temp", templateExample("custom_dev1_index_*"))

          val result = dev1TemplateManager.deleteTemplate("temp")

          result.responseCode should be(200)
        }
        "he previously added it" in {
          val addingResult = dev1TemplateManager.insertTemplate("new_template", templateExample("*"))
          addingResult.responseCode should be(200)

          val deletingResult = dev1TemplateManager.deleteTemplate("new_template")
          deletingResult.responseCode should be(200)
        }
      }
    }
    "user is dev2" should {
      "not be able to get templates" when {
        "there are no his templates but other user's one exists" when {
          "there is no index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

                val templates = dev2TemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.responseJson.obj.keys.size should be(0)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

                val templates = dev2TemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.responseJson.obj.keys.size should be(0)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

                val templates = dev2TemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.responseJson.obj.keys.size should be(0)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

                val templates = dev2TemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.responseJson.obj.keys.size should be(0)
              }
            }
          }
          "there is an index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val templates = dev2TemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.responseJson.obj.keys.size should be(0)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val templates = dev2TemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.responseJson.obj.keys.size should be(0)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val templates = dev2TemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.responseJson.obj.keys.size should be(0)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val templates = dev2TemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.responseJson.obj.keys.size should be(0)
              }
            }
          }
        }
      }
      "not be able to get specific, foreign template" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val templates = dev2TemplateManager.getTemplate("temp1")

              templates.responseCode should be(404)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val templates = dev2TemplateManager.getTemplate("temp1")

              templates.responseCode should be(404)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val templates = dev2TemplateManager.getTemplate("temp1")

              templates.responseCode should be(404)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val templates = dev2TemplateManager.getTemplate("temp1")

              templates.responseCode should be(404)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val templates = dev2TemplateManager.getTemplate("temp1")

              templates.responseCode should be(404)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val templates = dev2TemplateManager.getTemplate("temp1")

              templates.responseCode should be(404)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val templates = dev2TemplateManager.getTemplate("temp1")

              templates.responseCode should be(404)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val templates = dev2TemplateManager.getTemplate("temp1")

              templates.responseCode should be(404)
            }
          }
        }
      }
      "not be able to create template for foreign index pattern" when {
        "template has index pattern with wildcard" when {
          "rule has index pattern with wildcard" in {
            val result = dev2TemplateManager.insertTemplate("new_template", templateExample("custom_dev1_*"))

            result.responseCode should be(401)
          }
          "rule has index pattern with no wildcard" in {
            val result = dev2TemplateManager.insertTemplate("new_template", templateExample("dev1_*"))

            result.responseCode should be(401)
          }
        }
        "template has index pattern with no wildcard" when {
          "rule has index pattern with wildcard" in {
            val result = dev2TemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_test"))

            result.responseCode should be(401)
          }
          "rule has index pattern with no wildcard" in {
            val result = dev2TemplateManager.insertTemplate("new_template", templateExample("dev1_index"))

            result.responseCode should be(401)
          }
        }
        "template contains only not allowed index patterns" in {
          val result = dev2TemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_1*"))

          result.responseCode should be(401)
        }
        "template applies to allowed index and not allowed index patterns" excludeES (allEs5x) in {
          val result = dev2TemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_*", "custom_dev2_index_*"))

          result.responseCode should be(401)
        }
      }
      "not be able to override existing template" which {
        "doesn't belong to him" in {
          val dev1TemplateManager = new TemplateManager(basicAuthClient("dev1", "test"))
          val dev2TemplateManager = new TemplateManager(basicAuthClient("dev2", "test"))

          val result1 = dev1TemplateManager.insertTemplate("new_template", templateExample("custom_dev1*"))
          result1.responseCode should be(200)

          val result2 = dev2TemplateManager.insertTemplate("new_template", templateExample("custom_dev2*"))
          result2.responseCode should be(401)
        }
      }
      "not be able to delete foreign template" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val result = dev2TemplateManager.deleteTemplate("temp1")

              result.responseCode should be(401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val result = dev2TemplateManager.deleteTemplate("temp1")

              result.responseCode should be(401)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val result = dev2TemplateManager.deleteTemplate("temp1")

              result.responseCode should be(401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val result = dev2TemplateManager.deleteTemplate("temp1")

              result.responseCode should be(401)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val result = dev2TemplateManager.deleteTemplate("temp1")

              result.responseCode should be(401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val result = dev2TemplateManager.deleteTemplate("temp1")

              result.responseCode should be(401)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val result = dev2TemplateManager.deleteTemplate("temp1")

              result.responseCode should be(401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val result = dev2TemplateManager.deleteTemplate("temp1")

              result.responseCode should be(401)
            }
          }
        }
        "not all index patterns defined in template are allowed to the user" excludeES (allEs5x) in {
          adminTemplateManager.insertTemplateAndWaitForIndexing(
            "temp",
            templateExample("custom_dev1_index_*", "custom_dev2_index_*")
          )

          val result = dev2TemplateManager.deleteTemplate("temp")

          result.responseCode should be(401)
        }
        "index pattern defined in template are allowed to the user" in {
          adminTemplateManager.insertTemplateAndWaitForIndexing(
            "temp",
            templateExample("custom_dev2_index_*")
          )

          val result = dev2TemplateManager.deleteTemplate("temp")

          result.responseCode should be(401)
        }
        "index pattern defined in template are too generic" in {
          adminTemplateManager.insertTemplateAndWaitForIndexing("temp", templateExample("*"))

          val result = dev2TemplateManager.deleteTemplate("temp")

          result.responseCode should be(401)
        }
      }
    }
  }

  "A new template API" when {
    "put index template operation is used" should {
      "be allowed" when {
        "there is an index defined for it" when {
          "template has index pattern with wildcard" in {}
        }
        "indices rule is not used in matched block" in {
          val result = adminTemplateManager.putIndexTemplate(
            templateName = "temp1",
            indexPatterns = NonEmptyList.of("dev*"),
            template = indexTemplate("dev")
          )
          result.responseCode should be(200)
        }
        "user has access to all indices related data from request (index patterns, aliases, aliases patterns)" in {
          val result = dev1TemplateManager.putIndexTemplate(
            templateName = "temp1",
            indexPatterns = NonEmptyList.of("custom_dev1_index_a_*", "custom_dev1_index_b_*"),
            template = indexTemplate("dev1_index", "{index}_alias")
          )
          result.responseCode should be(200)
        }
      }
      "not be allowed" when {
        "user has no access to at least one requested index pattern" in {
          val result = dev2TemplateManager.putIndexTemplate(
            templateName = "temp1",
            indexPatterns = NonEmptyList.of("dev1*"),
            template = indexTemplate()
          )
          result.responseCode should be(401)
        }
        "user has no access to at least one requested alias" in {
          val result = dev2TemplateManager.putIndexTemplate(
            templateName = "temp1",
            indexPatterns = NonEmptyList.of("custom_dev2_index_a_**"),
            template = indexTemplate("dev1", "dev2_index")
          )
          result.responseCode should be(401)
        }
        "user has no access to at least one requested alias pattern" in {
          val result = dev2TemplateManager.putIndexTemplate(
            templateName = "temp1",
            indexPatterns = NonEmptyList.of("custom_dev2_index_a_**"),
            template = indexTemplate("alias_{index}", "dev2_index")
          )
          result.responseCode should be(401)
        }
      }
    }
    "delete index template operation is used" should {
      "be allowed" in {

      }
      "not be allowed" in {

      }
    }
  }
}

object IndexTemplatesManagementSuite {

  private object examples {

    def indexTemplate(aliases: String*): JSON = ujson.read {
      s"""
         |{
         |  "settings" : {
         |    "number_of_shards" : 1
         |  },
         |  "aliases" : {
         |    ${aliases.toList.map(alias => s""""$alias":{}""").mkString(",")}
         |  }
         |}
       """.stripMargin
    }
  }
}
