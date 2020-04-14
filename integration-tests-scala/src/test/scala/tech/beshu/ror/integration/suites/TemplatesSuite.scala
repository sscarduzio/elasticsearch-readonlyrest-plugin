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

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.base.BaseTemplatesSuite
import tech.beshu.ror.utils.containers.generic.{EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.TemplateManager

trait TemplatesSuite
  extends WordSpec
    with BaseTemplatesSuite {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/templates/readonlyrest.yml"

  override lazy val rorContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1"
    )
  )

  "A template API" when {
    "user is dev1" should {
      "see empty list of templates" when {
        "there is none" in {
          val devTemplateManager = new TemplateManager(client("dev1", "test"))
          val templates = devTemplateManager.getTemplates

          templates.responseCode should be(200)
          templates.responseJson.obj.size should be (0)
        }
      }
      "be allowed to get all templates" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val templates = devTemplateManager.getTemplates

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val templates = devTemplateManager.getTemplates

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val templates = devTemplateManager.getTemplates

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val templates = devTemplateManager.getTemplates

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

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val templates = devTemplateManager.getTemplates

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val templates = devTemplateManager.getTemplates

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val templates = devTemplateManager.getTemplates

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val templates = devTemplateManager.getTemplates

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

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

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

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.responseCode should be(200)
              templates.responseJson.obj.keys.toList should contain only "temp1"
            }
          }
        }
        "at least one template index pattern matches user's allowed indices" in {
          adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*", "custom_dev2_*"))
          createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")
          createIndexWithExampleDoc(adminDocumentManager, "custom_dev2_index_test")

          val devTemplateManager = new TemplateManager(rorContainer.nodesContainers.head.client("dev1", "test"))
          val templates = devTemplateManager.getTemplate("temp1")

          templates.responseCode should be(200)
          templates.responseJson.obj.keys.toList should contain only "temp1"
        }
      }
      "be allowed to create new template" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val result = devTemplateManager.insertTemplate("new_template", templateExample("custom_dev1_*"))

              result.responseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val result = devTemplateManager.insertTemplate("new_template", templateExample("dev1_*"))

              result.responseCode should be (200)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val result = devTemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_test"))

              result.responseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val result = devTemplateManager.insertTemplate("new_template", templateExample("dev1_index"))

              result.responseCode should be (200)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminDocumentManager.createDoc("/custom_dev1_index_test/doc/1", "{\"hello\":\"world\"}")

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val result = devTemplateManager.insertTemplate("new_template", templateExample("custom_dev1_*"))

              result.responseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              adminDocumentManager.createDoc("/dev1_index/doc/1", "{\"hello\":\"world\"}")

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val result = devTemplateManager.insertTemplate("new_template", templateExample("dev1_*"))

              result.responseCode should be (200)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminDocumentManager.createDoc("/custom_dev1_index_test/doc/1", "{\"hello\":\"world\"}")

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val result = devTemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_test"))

              result.responseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              adminDocumentManager.createDoc("/dev1_index/doc/1", "{\"hello\":\"world\"}")

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val result = devTemplateManager.insertTemplate("new_template", templateExample("dev1_index"))

              result.responseCode should be (200)
            }
          }
        }
        "template applies to generic index pattern" in {
          val devTemplateManager = new TemplateManager(rorContainer.nodesContainers.head.client("dev1", "test"))
          val result = devTemplateManager.insertTemplate("new_template", templateExample("custom_*"))

          result.responseCode should be (200)

          val user1Template = adminTemplateManager.getTemplate("new_template")
          user1Template.responseCode should be(200)
          user1Template.responseJson.obj("new_template").obj("index_patterns").arr.map(_.str).toList should be (
            "custom_*" :: Nil
          )
        }
      }
      "be allowed to override existing template" which {
        "belongs to him" in {
          val dev1TemplateManager = new TemplateManager(rorContainer.nodesContainers.head.client("dev1", "test"))

          val insert1Result =
            dev1TemplateManager.insertTemplate("new_template", templateExample("custom_dev1*"))
          insert1Result.responseCode should be (200)

          val user1Template = dev1TemplateManager.getTemplates
          user1Template.responseCode should be (200)
          user1Template.responseJson.obj("new_template").obj("index_patterns").arr.map(_.str).toList should be (
            "custom_dev1_index_*" :: Nil
          )

          val insert2Result =
            dev1TemplateManager.insertTemplate("new_template", templateExample("dev1_index*"))
          insert2Result.responseCode should be (200)

          val user1TemplateAfterOverride = dev1TemplateManager.getTemplates
          user1TemplateAfterOverride.responseCode should be (200)
          user1TemplateAfterOverride.responseJson.obj("new_template").obj("index_patterns").arr.map(_.str).toList should be (
            "dev1_index" :: Nil
          )
        }
      }
      "be allowed to remove his template" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("custom_dev1_*"))

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val result = devTemplateManager.deleteTemplate("temp_to_remove")

              result.responseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("dev1_*"))

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val result = devTemplateManager.deleteTemplate("temp_to_remove")

              result.responseCode should be (200)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("custom_dev1_index_test"))

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val result = devTemplateManager.deleteTemplate("temp_to_remove")

              result.responseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("dev1_index"))

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val result = devTemplateManager.deleteTemplate("temp_to_remove")

              result.responseCode should be (200)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val result = devTemplateManager.deleteTemplate("temp_to_remove")

              result.responseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val result = devTemplateManager.deleteTemplate("temp_to_remove")

              result.responseCode should be (200)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val result = devTemplateManager.deleteTemplate("temp_to_remove")

              result.responseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManager(client("dev1", "test"))
              val result = devTemplateManager.deleteTemplate("temp_to_remove")

              result.responseCode should be (200)
            }
          }
        }
        "all indices patterns defined in template are allowed to the user" in {
          adminTemplateManager.insertTemplateAndWaitForIndexing("temp", templateExample("custom_dev1_*", "dev1_index*"))

          val devTemplateManager = new TemplateManager(rorContainer.nodesContainers.head.client("dev1", "test"))
          val result = devTemplateManager.deleteTemplate("temp")

          result.responseCode should be (200)
        }
        "he previously added it" in {
          val devTemplateManager = new TemplateManager(rorContainer.nodesContainers.head.client("dev1", "test"))

          val addingResult = devTemplateManager.insertTemplate("new_template", templateExample("*"))
          addingResult.responseCode should be (200)

          val deletingResult = devTemplateManager.deleteTemplate("new_template")
          deletingResult.responseCode should be (200)
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

                val devTemplateManager = new TemplateManager(client("dev2", "test"))
                val templates = devTemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.responseJson.obj.keys.size should be(0)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

                val devTemplateManager = new TemplateManager(client("dev2", "test"))
                val templates = devTemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.responseJson.obj.keys.size should be(0)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

                val devTemplateManager = new TemplateManager(client("dev2", "test"))
                val templates = devTemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.responseJson.obj.keys.size should be(0)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

                val devTemplateManager = new TemplateManager(client("dev2", "test"))
                val templates = devTemplateManager.getTemplates

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

                val devTemplateManager = new TemplateManager(client("dev2", "test"))
                val templates = devTemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.responseJson.obj.keys.size should be(0)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val devTemplateManager = new TemplateManager(client("dev2", "test"))
                val templates = devTemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.responseJson.obj.keys.size should be(0)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val devTemplateManager = new TemplateManager(client("dev2", "test"))
                val templates = devTemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.responseJson.obj.keys.size should be(0)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val devTemplateManager = new TemplateManager(client("dev2", "test"))
                val templates = devTemplateManager.getTemplates

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

              val devTemplateManager = new TemplateManager(client("dev2", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.responseCode should be(404)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val devTemplateManager = new TemplateManager(client("dev2", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.responseCode should be(404)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val devTemplateManager = new TemplateManager(client("dev2", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.responseCode should be(404)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val devTemplateManager = new TemplateManager(client("dev2", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.responseCode should be(404)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManager(client("dev2", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.responseCode should be(404)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManager(client("dev2", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.responseCode should be(404)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManager(client("dev2", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.responseCode should be(404)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManager(client("dev2", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.responseCode should be(404)
            }
          }
        }
      }
      "not be able to create template for foreign index pattern" when {
        "template has index pattern with wildcard" when {
          "rule has index pattern with wildcard" in {
            val devTemplateManager = new TemplateManager(client("dev2", "test"))
            val result = devTemplateManager.insertTemplate("new_template", templateExample("custom_dev1_*"))

            result.responseCode should be (401)
          }
          "rule has index pattern with no wildcard" in {
            val devTemplateManager = new TemplateManager(client("dev2", "test"))
            val result = devTemplateManager.insertTemplate("new_template", templateExample("dev1_*"))

            result.responseCode should be (401)
          }
        }
        "template has index pattern with no wildcard" when {
          "rule has index pattern with wildcard" in {
            val devTemplateManager = new TemplateManager(client("dev2", "test"))
            val result = devTemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_test"))

            result.responseCode should be (401)
          }
          "rule has index pattern with no wildcard" in {
            val devTemplateManager = new TemplateManager(client("dev2", "test"))
            val result = devTemplateManager.insertTemplate("new_template", templateExample("dev1_index"))

            result.responseCode should be (401)
          }
        }
        "template contains only not allowed index patterns" in {
          val devTemplateManager = new TemplateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
          val result = devTemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_1*", "custom_dev1_index_2*"))

          result.responseCode should be (401)
        }
        "template applies to allowed index and not allowed index patterns and only allowed index pattern is taken" in {
          val devTemplateManager = new TemplateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
          val result = devTemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_*", "custom_dev2_index_*"))

          result.responseCode should be (401)
        }
      }
      "not be able to override existing template" which {
        "doesn't belong to him" in {
          val dev1TemplateManager = new TemplateManager(rorContainer.nodesContainers.head.client("dev1", "test"))
          val dev2TemplateManager = new TemplateManager(rorContainer.nodesContainers.head.client("dev2", "test"))

          val result1 = dev1TemplateManager.insertTemplate("new_template", templateExample("custom_dev1*"))
          result1.responseCode should be (200)

          val result2 = dev2TemplateManager.insertTemplate("new_template", templateExample("custom_dev2*"))
          result2.responseCode should be (401)
        }
      }
      "not be able to delete foreign template" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val devTemplateManager = new TemplateManager(client("dev2", "test"))
              val result = devTemplateManager.deleteTemplate("temp1")

              result.responseCode should be (401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val devTemplateManager = new TemplateManager(client("dev2", "test"))
              val result = devTemplateManager.deleteTemplate("temp1")

              result.responseCode should be (401)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val devTemplateManager = new TemplateManager(client("dev2", "test"))
              val result = devTemplateManager.deleteTemplate("temp1")

              result.responseCode should be (401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val devTemplateManager = new TemplateManager(client("dev2", "test"))
              val result = devTemplateManager.deleteTemplate("temp1")

              result.responseCode should be (401)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManager(client("dev2", "test"))
              val result = devTemplateManager.deleteTemplate("temp1")

              result.responseCode should be (401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManager(client("dev2", "test"))
              val result = devTemplateManager.deleteTemplate("temp1")

              result.responseCode should be (401)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManager(client("dev2", "test"))
              val result = devTemplateManager.deleteTemplate("temp1")

              result.responseCode should be (401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManager(client("dev2", "test"))
              val result = devTemplateManager.deleteTemplate("temp1")

              result.responseCode should be (401)
            }
          }
        }
        "not all index patterns defined in template are allowed to the user" in {
          adminTemplateManager.insertTemplateAndWaitForIndexing(
            "temp",
            templateExample("custom_dev1_index_*", "custom_dev2_index_*")
          )

          val devTemplateManager = new TemplateManager(rorContainer.nodesContainers.head.client("dev1", "test"))
          val result = devTemplateManager.deleteTemplate("temp")

          result.responseCode should be (401)
        }
        "index pattern defined in template are too generic" in {
          adminTemplateManager.insertTemplateAndWaitForIndexing("temp", templateExample("*"))

          val devTemplateManager = new TemplateManager(rorContainer.nodesContainers.head.client("dev1", "test"))
          val result = devTemplateManager.deleteTemplate("temp")

          result.responseCode should be (401)
        }
      }
    }
  }
}
