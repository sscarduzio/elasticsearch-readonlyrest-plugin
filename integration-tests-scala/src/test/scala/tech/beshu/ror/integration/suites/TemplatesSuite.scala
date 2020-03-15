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
import tech.beshu.ror.utils.elasticsearch.TemplateManagerJ

import scala.collection.JavaConverters._

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
          val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
          val templates = devTemplateManager.getTemplates

          templates.getResponseCode should be(200)
          templates.getResponseJsonMap.size() should be (0)
        }
      }
      "be allowed to get all templates" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val templates = devTemplateManager.getTemplates

              templates.getResponseCode should be(200)
              templates.getResponseJsonMap.asScala.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val templates = devTemplateManager.getTemplates

              templates.getResponseCode should be(200)
              templates.getResponseJsonMap.asScala.keys.toList should contain only "temp1"
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val templates = devTemplateManager.getTemplates

              templates.getResponseCode should be(200)
              templates.getResponseJsonMap.asScala.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val templates = devTemplateManager.getTemplates

              templates.getResponseCode should be(200)
              templates.getResponseJsonMap.asScala.keys.toList should contain only "temp1"
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val templates = devTemplateManager.getTemplates

              templates.getResponseCode should be(200)
              templates.getResponseJsonMap.asScala.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val templates = devTemplateManager.getTemplates

              templates.getResponseCode should be(200)
              templates.getResponseJsonMap.asScala.keys.toList should contain only "temp1"
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val templates = devTemplateManager.getTemplates

              templates.getResponseCode should be(200)
              templates.getResponseJsonMap.asScala.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val templates = devTemplateManager.getTemplates

              templates.getResponseCode should be(200)
              templates.getResponseJsonMap.asScala.keys.toList should contain only "temp1"
            }
          }
        }
      }
      "be allowed to get specific template" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(200)
              templates.getResponseJsonMap.asScala.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(200)
              templates.getResponseJsonMap.asScala.keys.toList should contain only "temp1"
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(200)
              templates.getResponseJsonMap.asScala.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(200)
              templates.getResponseJsonMap.asScala.keys.toList should contain only "temp1"
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(200)
              templates.getResponseJsonMap.asScala.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(200)
              templates.getResponseJsonMap.asScala.keys.toList should contain only "temp1"
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(200)
              templates.getResponseJsonMap.asScala.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(200)
              templates.getResponseJsonMap.asScala.keys.toList should contain only "temp1"
            }
          }
        }
      }
      "be allowed to create new template" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val result = devTemplateManager.insertTemplate("new_template", templateExample("custom_dev1_*"))

              result.getResponseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val result = devTemplateManager.insertTemplate("new_template", templateExample("dev1_*"))

              result.getResponseCode should be (200)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val result = devTemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_test"))

              result.getResponseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val result = devTemplateManager.insertTemplate("new_template", templateExample("dev1_index"))

              result.getResponseCode should be (200)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/doc/1", "{\"hello\":\"world\"}")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val result = devTemplateManager.insertTemplate("new_template", templateExample("custom_dev1_*"))

              result.getResponseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/doc/1", "{\"hello\":\"world\"}")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val result = devTemplateManager.insertTemplate("new_template", templateExample("dev1_*"))

              result.getResponseCode should be (200)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/doc/1", "{\"hello\":\"world\"}")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val result = devTemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_test"))

              result.getResponseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/doc/1", "{\"hello\":\"world\"}")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val result = devTemplateManager.insertTemplate("new_template", templateExample("dev1_index"))

              result.getResponseCode should be (200)
            }
          }
        }
      }
      "be allowed to remove his template" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("custom_dev1_*"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val result = devTemplateManager.deleteTemplate("temp_to_remove")

              result.getResponseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("dev1_*"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val result = devTemplateManager.deleteTemplate("temp_to_remove")

              result.getResponseCode should be (200)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("custom_dev1_index_test"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val result = devTemplateManager.deleteTemplate("temp_to_remove")

              result.getResponseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("dev1_index"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val result = devTemplateManager.deleteTemplate("temp_to_remove")

              result.getResponseCode should be (200)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val result = devTemplateManager.deleteTemplate("temp_to_remove")

              result.getResponseCode should be (200)

            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val result = devTemplateManager.deleteTemplate("temp_to_remove")

              result.getResponseCode should be (200)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val result = devTemplateManager.deleteTemplate("temp_to_remove")

              result.getResponseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev1", "test"))
              val result = devTemplateManager.deleteTemplate("temp_to_remove")

              result.getResponseCode should be (200)
            }
          }
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

                val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
                val templates = devTemplateManager.getTemplates

                templates.getResponseCode should be(200)
                templates.getResponseJsonMap.size() should be(0)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

                val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
                val templates = devTemplateManager.getTemplates

                templates.getResponseCode should be(200)
                templates.getResponseJsonMap.size() should be(0)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

                val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
                val templates = devTemplateManager.getTemplates

                templates.getResponseCode should be(200)
                templates.getResponseJsonMap.size() should be(0)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

                val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
                val templates = devTemplateManager.getTemplates

                templates.getResponseCode should be(200)
                templates.getResponseJsonMap.size() should be(0)
              }
            }
          }
          "there is an index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
                val templates = devTemplateManager.getTemplates

                templates.getResponseCode should be(200)
                templates.getResponseJsonMap.size() should be(0)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
                val templates = devTemplateManager.getTemplates

                templates.getResponseCode should be(200)
                templates.getResponseJsonMap.size() should be(0)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
                val templates = devTemplateManager.getTemplates

                templates.getResponseCode should be(200)
                templates.getResponseJsonMap.size() should be(0)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
                val templates = devTemplateManager.getTemplates

                templates.getResponseCode should be(200)
                templates.getResponseJsonMap.size() should be(0)
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

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(404)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(404)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(404)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(404)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(404)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(404)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(404)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
              val templates = devTemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(404)
            }
          }
        }
      }
      "not be able to create template for foreign index pattern" when {
        "template has index pattern with wildcard" when {
          "rule has index pattern with wildcard" in {
            val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
            val result = devTemplateManager.insertTemplate("new_template", templateExample("custom_dev1_*"))

            result.getResponseCode should be (401)
          }
          "rule has index pattern with no wildcard" in {
            val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
            val result = devTemplateManager.insertTemplate("new_template", templateExample("dev1_*"))

            result.getResponseCode should be (401)
          }
        }
        "template has index pattern with no wildcard" when {
          "rule has index pattern with wildcard" in {
            val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
            val result = devTemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_test"))

            result.getResponseCode should be (401)
          }
          "rule has index pattern with no wildcard" in {
            val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
            val result = devTemplateManager.insertTemplate("new_template", templateExample("dev1_index"))

            result.getResponseCode should be (401)
          }
        }
      }
      "not be able to delete foreign template" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
              val result = devTemplateManager.deleteTemplate("temp1")

              result.getResponseCode should be (401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
              val result = devTemplateManager.deleteTemplate("temp1")

              result.getResponseCode should be (401)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
              val result = devTemplateManager.deleteTemplate("temp1")

              result.getResponseCode should be (401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
              val result = devTemplateManager.deleteTemplate("temp1")

              result.getResponseCode should be (401)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
              val result = devTemplateManager.deleteTemplate("temp1")

              result.getResponseCode should be (401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
              val result = devTemplateManager.deleteTemplate("temp1")

              result.getResponseCode should be (401)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
              val result = devTemplateManager.deleteTemplate("temp1")

              result.getResponseCode should be (401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val devTemplateManager = new TemplateManagerJ(basicAuthClient("dev2", "test"))
              val result = devTemplateManager.deleteTemplate("temp1")

              result.getResponseCode should be (401)
            }
          }
        }
      }
    }
  }
}
