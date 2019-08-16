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
package tech.beshu.ror.integration

import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest.Matchers._
import org.scalatest.{BeforeAndAfterEach, WordSpec}
import tech.beshu.ror.utils.containers.{ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{ClusterStateManager, DocumentManager, IndexManager, TemplateManager}
import tech.beshu.ror.utils.misc.Version

import scala.collection.JavaConverters._

class TemplatesTests extends WordSpec with ForAllTestContainer with BeforeAndAfterEach {
  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/templates/readonlyrest.yml",
    numberOfInstances = 1
  )

  private lazy val adminTemplateManager = new TemplateManager(container.nodesContainers.head.adminClient)
  private lazy val adminDocumentManager = new DocumentManager(container.nodesContainers.head.adminClient)

  "A template API" when {
    "user is dev1" should {
      "be allowed to get all templates using /_cat/templates" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val dev1ClusterStateManager = new ClusterStateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1ClusterStateManager.catTemplates()

              templates.getResponseCode should be(200)
              templates.getResults.size() should be (1)
              templates.getResults.asScala.head should include ("temp1")
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val dev1ClusterStateManager = new ClusterStateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1ClusterStateManager.catTemplates()

              templates.getResponseCode should be(200)
              templates.getResults.size() should be (1)
              templates.getResults.asScala.head should include ("temp1")
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val dev1ClusterStateManager = new ClusterStateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1ClusterStateManager.catTemplates()

              templates.getResponseCode should be(200)
              templates.getResults.size() should be (1)
              templates.getResults.asScala.head should include ("temp1")
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val dev1ClusterStateManager = new ClusterStateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1ClusterStateManager.catTemplates()

              templates.getResponseCode should be(200)
              templates.getResults.size() should be (1)
              templates.getResults.asScala.head should include ("temp1")
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/_doc/1", "{\"hello\":\"world\"}")

              val dev1ClusterStateManager = new ClusterStateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1ClusterStateManager.catTemplates()

              templates.getResponseCode should be(200)
              templates.getResults.size() should be (1)
              templates.getResults.asScala.head should include ("temp1")
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/_doc/1", "{\"hello\":\"world\"}")

              val dev1ClusterStateManager = new ClusterStateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1ClusterStateManager.catTemplates()

              templates.getResponseCode should be(200)
              templates.getResults.size() should be (1)
              templates.getResults.asScala.head should include ("temp1")
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/_doc/1", "{\"hello\":\"world\"}")

              val dev1ClusterStateManager = new ClusterStateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1ClusterStateManager.catTemplates()

              templates.getResponseCode should be(200)
              templates.getResults.size() should be (1)
              templates.getResults.asScala.head should include ("temp1")
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/_doc/1", "{\"hello\":\"world\"}")

              val dev1ClusterStateManager = new ClusterStateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1ClusterStateManager.catTemplates()

              templates.getResponseCode should be(200)
              templates.getResults.size() should be (1)
              templates.getResults.asScala.head should include ("temp1")
            }
          }
        }
      }
      "be allowed to get all templates" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1TemplateManager.getTemplates

              templates.getResponseCode should be(200)
              templates.getResults.asScala.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1TemplateManager.getTemplates

              templates.getResponseCode should be(200)
              templates.getResults.asScala.keys.toList should contain only "temp1"
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1TemplateManager.getTemplates

              templates.getResponseCode should be(200)
              templates.getResults.asScala.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1TemplateManager.getTemplates

              templates.getResponseCode should be(200)
              templates.getResults.asScala.keys.toList should contain only "temp1"
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1TemplateManager.getTemplates

              templates.getResponseCode should be(200)
              templates.getResults.asScala.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1TemplateManager.getTemplates

              templates.getResponseCode should be(200)
              templates.getResults.asScala.keys.toList should contain only "temp1"
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1TemplateManager.getTemplates

              templates.getResponseCode should be(200)
              templates.getResults.asScala.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1TemplateManager.getTemplates

              templates.getResponseCode should be(200)
              templates.getResults.asScala.keys.toList should contain only "temp1"
            }
          }
        }
      }
      "be allowed to get specific template" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(200)
              templates.getResults.asScala.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(200)
              templates.getResults.asScala.keys.toList should contain only "temp1"
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(200)
              templates.getResults.asScala.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(200)
              templates.getResults.asScala.keys.toList should contain only "temp1"
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(200)
              templates.getResults.asScala.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(200)
              templates.getResults.asScala.keys.toList should contain only "temp1"
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(200)
              templates.getResults.asScala.keys.toList should contain only "temp1"
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(200)
              templates.getResults.asScala.keys.toList should contain only "temp1"
            }
          }
        }
      }
      "be allowed to create new template" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val result = dev1TemplateManager.insertTemplate("new_template", templateExample("custom_dev1_*"))

              result.getResponseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val result = dev1TemplateManager.insertTemplate("new_template", templateExample("dev1_*"))

              result.getResponseCode should be (200)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val result = dev1TemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_test"))

              result.getResponseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val result = dev1TemplateManager.insertTemplate("new_template", templateExample("dev1_index"))

              result.getResponseCode should be (200)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val result = dev1TemplateManager.insertTemplate("new_template", templateExample("custom_dev1_*"))

              result.getResponseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val result = dev1TemplateManager.insertTemplate("new_template", templateExample("dev1_*"))

              result.getResponseCode should be (200)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val result = dev1TemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_test"))

              result.getResponseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val result = dev1TemplateManager.insertTemplate("new_template", templateExample("dev1_index"))

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

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

              result.getResponseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("dev1_*"))

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

              result.getResponseCode should be (200)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("custom_dev1_index_test"))

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

              result.getResponseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("dev1_index"))

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

              result.getResponseCode should be (200)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("custom_dev1_*"))
              adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

              result.getResponseCode should be (200)

            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("dev1_*"))
              adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

              result.getResponseCode should be (200)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("custom_dev1_index_test"))
              adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

              result.getResponseCode should be (200)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp_to_remove", templateExample("dev1_index"))
              adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
              val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

              result.getResponseCode should be (200)
            }
          }
        }
      }
      "not be allowed to get templates" when {
        "there is none" in {
          val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev1", "test"))
          val templates = dev1TemplateManager.getTemplates

          templates.getResponseCode should be(401)
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

                val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
                val templates = dev1TemplateManager.getTemplates

                templates.getResponseCode should be(401)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

                val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
                val templates = dev1TemplateManager.getTemplates

                templates.getResponseCode should be(401)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

                val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
                val templates = dev1TemplateManager.getTemplates

                templates.getResponseCode should be(401)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

                val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
                val templates = dev1TemplateManager.getTemplates

                templates.getResponseCode should be(401)
              }
            }
          }
          "there is an index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
                adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/_doc/1", "{\"hello\":\"world\"}")

                val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
                val templates = dev1TemplateManager.getTemplates

                templates.getResponseCode should be(401)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
                adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/_doc/1", "{\"hello\":\"world\"}")

                val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
                val templates = dev1TemplateManager.getTemplates

                templates.getResponseCode should be(401)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
                adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/_doc/1", "{\"hello\":\"world\"}")

                val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
                val templates = dev1TemplateManager.getTemplates

                templates.getResponseCode should be(401)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
                adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/_doc/1", "{\"hello\":\"world\"}")

                val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
                val templates = dev1TemplateManager.getTemplates

                templates.getResponseCode should be(401)
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

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(401)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(401)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(401)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/_doc/1", "{\"hello\":\"world\"}")

              val dev1TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
              val templates = dev1TemplateManager.getTemplate("temp1")

              templates.getResponseCode should be(401)
            }
          }
        }
      }
      "not be able to create template for foreign index pattern" when {
        "template has index pattern with wildcard" when {
          "rule has index pattern with wildcard" in {
            val dev2TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
            val result = dev2TemplateManager.insertTemplate("new_template", templateExample("custom_dev1_*"))

            result.getResponseCode should be (401)
          }
          "rule has index pattern with no wildcard" in {
            val dev2TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
            val result = dev2TemplateManager.insertTemplate("new_template", templateExample("dev1_*"))

            result.getResponseCode should be (401)
          }
        }
        "template has index pattern with no wildcard" when {
          "rule has index pattern with wildcard" in {
            val dev2TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
            val result = dev2TemplateManager.insertTemplate("new_template", templateExample("custom_dev1_index_test"))

            result.getResponseCode should be (401)
          }
          "rule has index pattern with no wildcard" in {
            val dev2TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
            val result = dev2TemplateManager.insertTemplate("new_template", templateExample("dev1_index"))

            result.getResponseCode should be (401)
          }
        }
      }
      "not be able to delete foreign template" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val dev2TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
              val result = dev2TemplateManager.deleteTemplate("temp1")

              result.getResponseCode should be (401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val dev2TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
              val result = dev2TemplateManager.deleteTemplate("temp1")

              result.getResponseCode should be (401)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val dev2TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
              val result = dev2TemplateManager.deleteTemplate("temp1")

              result.getResponseCode should be (401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val dev2TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
              val result = dev2TemplateManager.deleteTemplate("temp1")

              result.getResponseCode should be (401)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/_doc/1", "{\"hello\":\"world\"}")

              val dev2TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
              val result = dev2TemplateManager.deleteTemplate("temp1")

              result.getResponseCode should be (401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/_doc/1", "{\"hello\":\"world\"}")

              val dev2TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
              val result = dev2TemplateManager.deleteTemplate("temp1")

              result.getResponseCode should be (401)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              adminDocumentManager.insertDocAndWaitForRefresh("/custom_dev1_index_test/_doc/1", "{\"hello\":\"world\"}")

              val dev2TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
              val result = dev2TemplateManager.deleteTemplate("temp1")

              result.getResponseCode should be (401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              adminDocumentManager.insertDocAndWaitForRefresh("/dev1_index/_doc/1", "{\"hello\":\"world\"}")

              val dev2TemplateManager = new TemplateManager(container.nodesContainers.head.client("dev2", "test"))
              val result = dev2TemplateManager.deleteTemplate("temp1")

              result.getResponseCode should be (401)
            }
          }
        }
      }
    }
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    truncateTemplates()
    truncateIndices()
    addControlTemplate()
  }

  private def truncateTemplates(): Unit = {
    val templates = adminTemplateManager.getTemplates
    if(templates.getResponseCode != 200) throw new IllegalStateException("Cannot get all templates by admin")
    templates
      .getResults.keySet().asScala
      .foreach { template =>
        val deleteTemplateResult = adminTemplateManager.deleteTemplate(template)
        if(deleteTemplateResult.getResponseCode != 200) throw new IllegalStateException(s"Admin cannot delete '$template' template")
      }
  }

  private def truncateIndices(): Unit = {
    val indicesManager = new IndexManager(container.nodesContainers.head.adminClient)
    if(indicesManager.removeAll().getResponseCode != 200) {
      throw new IllegalStateException("Admin cannot remove all indices")
    }
  }

  private def addControlTemplate(): TemplateManager.TemplateOperationResult = {
    adminTemplateManager.insertTemplate("control_one", templateExample("control_*"))
  }

  private def templateExample(indexPattern: String) = {
    val esVersion = container.esVersion
    if(Version.greaterOrEqualThan(esVersion, 7, 0, 0)) {
      s"""{"index_patterns":["$indexPattern"],"settings":{"number_of_shards":1},"mappings":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}}"""
    } else if(Version.greaterOrEqualThan(esVersion, 6, 1, 0)) {
      s"""{"index_patterns":["$indexPattern"],"settings":{"number_of_shards":1},"mappings":{"_doc":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}}}"""
    } else {
      s"""{"template":"$indexPattern","settings":{"number_of_shards":1},"mappings":{"doc":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}}}"""
    }
  }
}
