package tech.beshu.ror.integration

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.base.BaseTemplatesTests
import tech.beshu.ror.utils.containers.{ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.ClusterStateManager
import ujson.Str

class CatApiTests extends WordSpec with BaseTemplatesTests {

  override lazy val rorContainer: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/indices_api/readonlyrest.yml",
    numberOfInstances = 1
  )

  private lazy val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev1", "test"))
  private lazy val dev2ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))

  "A _cat/indices API" when {
    "return empty indices" when {
      "there is no index in ES" in {
        val indices = dev1ClusterStateManager.catIndices()

        indices.getResponseCode should be(200)
        indices.results.size should be (0)
      }
      "dev1 has no indices" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev1ClusterStateManager.catIndices()

        indices.getResponseCode should be(200)
        indices.results.size should be (0)
      }
    }
    "return only dev1 indices" when {
      "request is related to all indices" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev1_index")
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev1ClusterStateManager.catIndices()

        indices.getResponseCode should be(200)
        indices.results.size should be (1)
        indices.results(0)("index") should be (Str("dev1_index"))
      }
      "request is related to one index" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev1_index")
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev1ClusterStateManager.catIndices("dev1_index")

        indices.getResponseCode should be(200)
        indices.results.size should be (1)
        indices.results(0)("index") should be (Str("dev1_index"))
      }
      "request index has wildcard" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev1_index")
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev1ClusterStateManager.catIndices("dev*")

        indices.getResponseCode should be(200)
        indices.results.size should be (1)
        indices.results(0)("index") should be (Str("dev1_index"))
      }
    }
    "return forbidden" when {
      "user has no access to given index" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev1ClusterStateManager.catIndices("dev2_index")

        indices.getResponseCode should be(401)
      }
      "user is trying to access non-existent index" in {
        val indices = dev1ClusterStateManager.catIndices("non-existent")

        indices.getResponseCode should be(404)
      }
    }
  }

  "A _cat/template API" when {
    "user is dev1" should {
      "be allowed to get all templates using /_cat/templates API" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val templates = dev1ClusterStateManager.catTemplates()

              templates.getResponseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val templates = dev1ClusterStateManager.catTemplates()

              templates.getResponseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val templates = dev1ClusterStateManager.catTemplates()

              templates.getResponseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val templates = dev1ClusterStateManager.catTemplates()

              templates.getResponseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val templates = dev1ClusterStateManager.catTemplates()

              templates.getResponseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val templates = dev1ClusterStateManager.catTemplates()

              templates.getResponseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val templates = dev1ClusterStateManager.catTemplates()

              templates.getResponseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val templates = dev1ClusterStateManager.catTemplates()

              templates.getResponseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
          }
        }
      }
      "be allowed to get specific template using /_cat/templates API" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.getResponseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.getResponseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.getResponseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.getResponseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.getResponseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.getResponseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.getResponseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.getResponseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
          }
        }
      }
    }
    "user is dev2" should {
      "not be able to get templates using /_cat/templates API" when {
        "there are no his templates but other user's one exists" when {
          "there is no index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

                val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
                val templates = dev1ClusterStateManager.catTemplates()

                templates.getResponseCode should be(401)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

                val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
                val templates = dev1ClusterStateManager.catTemplates()

                templates.getResponseCode should be(401)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

                val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
                val templates = dev1ClusterStateManager.catTemplates()

                templates.getResponseCode should be(401)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

                val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
                val templates = dev1ClusterStateManager.catTemplates()

                templates.getResponseCode should be(401)
              }
            }
          }
          "there is an index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val templates = dev2ClusterStateManager.catTemplates()

                templates.getResponseCode should be(401)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val templates = dev2ClusterStateManager.catTemplates()

                templates.getResponseCode should be(401)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val templates = dev2ClusterStateManager.catTemplates()

                templates.getResponseCode should be(401)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val templates = dev2ClusterStateManager.catTemplates()

                templates.getResponseCode should be(401)
              }
            }
          }
        }
      }
      "not be able to get specific, foreign template using /_cat/templates API" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.getResponseCode should be(401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.getResponseCode should be(401)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.getResponseCode should be(401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.getResponseCode should be(401)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.getResponseCode should be(401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.getResponseCode should be(401)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.getResponseCode should be(401)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.getResponseCode should be(401)
            }
          }
        }
      }
    }
  }
}
