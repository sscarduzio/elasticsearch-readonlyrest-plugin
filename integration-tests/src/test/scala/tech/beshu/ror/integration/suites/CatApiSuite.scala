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
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.BaseTemplatesSuite
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, SingletonPluginTestSupport}
import tech.beshu.ror.utils.elasticsearch.{BaseTemplateManager, CatManager, IndexTemplateManager, LegacyTemplateManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

class CatApiSuite
  extends AnyWordSpec
    with BaseTemplatesSuite
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/cat_api/readonlyrest.yml"

  private lazy val dev1CatManager = new CatManager(basicAuthClient("dev1", "test"), esVersion = esVersionUsed)
  private lazy val dev2CatManager = new CatManager(basicAuthClient("dev2", "test"), esVersion = esVersionUsed)
  private lazy val dev3CatManager = new CatManager(basicAuthClient("dev3", "test"), esVersion = esVersionUsed)

  "A /_cat" should {
    "be allowed when user is authorized" in {
      val response = dev1CatManager.cat()
      response should have statusCode 200
    }
    "be not allowed when user is not authorized" in {
      val unknownUserCatManager = new CatManager(basicAuthClient("unknown", "unknown"), esVersion = esVersionUsed)
      val response = unknownUserCatManager.cat()
      response should have statusCode 403
    }
  }

  "A /_cat/state" should {
    "work as expected" in {
      lazy val adminClusterStateManager = new CatManager(adminClient, esVersion = esVersionUsed)
      val response = adminClusterStateManager.healthCheck()

      response should have statusCode 200
    }
  }

  "A /_cat/indices API" should {
    "return empty indices" when {
      "there is no index in ES" in {
        val indices = dev1CatManager.indices()

        indices should have statusCode 200
        indices.results.map(_("index").str) should be(Vector.empty)
      }
      "dev1 has no indices" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev1CatManager.indices()

        indices should have statusCode 200
        indices.results.map(_("index").str) should be(Vector.empty)
      }
      "user asked for index with wildcard but there is no matching index which belongs to the user" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev1CatManager.indices("dev2_*")

        indices should have statusCode 200
        indices.results.map(_("index").str) should be(Vector.empty)
      }
    }
    "return only dev1 indices" when {
      "request is related to all indices" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev1_index")
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev1CatManager.indices()

        indices should have statusCode 200
        indices.results.map(_("index").str) should be(Vector("dev1_index"))
      }
      "request is related to one index" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev1_index")
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev1CatManager.indices("dev1_index")

        indices should have statusCode 200
        indices.results.map(_("index").str) should be(Vector("dev1_index"))
      }
      "request index has wildcard" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev1_index")
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev1CatManager.indices("dev*")

        indices should have statusCode 200
        indices.results.map(_("index").str) should be(Vector("dev1_index"))
      }
    }
    "return all indices" when {
      "user 3 is asking for them (his block doesn't contain `indices` rule)" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev1_index")
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev3CatManager.indices()

        indices should have statusCode 200
        indices.results.map(_("index").str) should contain allOf("dev1_index", "dev2_index")
      }
    }
    "return 404" when {
      "user is trying to access non-existent index" in {
        val indices = dev1CatManager.indices("non-existent")

        indices should have statusCode 404
      }
      "user asked for index with no access to it" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev1CatManager.indices("dev2_index")

        indices should have statusCode 404
      }
    }
  }

  indexTemplateApiTests("A /_cat/template API (legacy templates)")(new LegacyTemplateManager(_, esVersionUsed))
  if (doesSupportIndexTemplates) indexTemplateApiTests("A /_cat/template API (index templates)")(new IndexTemplateManager(_, esVersionUsed))

  private def indexTemplateApiTests(name: String)
                                   (templateManagerCreator: RestClient => BaseTemplateManager): Unit = {
    val adminTemplateManager = templateManagerCreator(adminClient)

    s"$name" when {
      "user is dev1" should {
        "be allowed to get all templates using /_cat/templates API" when {
          "there is no index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_*")
                )

                val templates = dev1CatManager.templates()

                templates should have statusCode 200
                templates.results.map(_("name").str).toList should contain("temp1")
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_*")
                )

                val templates = dev1CatManager.templates()

                templates should have statusCode 200
                templates.results.map(_("name").str).toList should contain("temp1")
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_index_test")
                )

                val templates = dev1CatManager.templates()

                templates should have statusCode 200
                templates.results.map(_("name").str).toList should contain("temp1")
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_index")
                )

                val templates = dev1CatManager.templates()

                templates should have statusCode 200
                templates.results.map(_("name").str).toList should contain("temp1")
              }
            }
          }
          "there is an index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_*")
                )
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val templates = dev1CatManager.templates()

                templates should have statusCode 200
                templates.results.map(_("name").str).toList should contain("temp1")
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_*")
                )
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val templates = dev1CatManager.templates()

                templates should have statusCode 200
                templates.results.map(_("name").str).toList should contain("temp1")
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_index_test")
                )
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val templates = dev1CatManager.templates()

                templates should have statusCode 200
                templates.results.map(_("name").str).toList should contain("temp1")
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_index")
                )
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val templates = dev1CatManager.templates()

                templates should have statusCode 200
                templates.results.map(_("name").str).toList should contain("temp1")
              }
            }
          }
        }
        "be allowed to get specific template using /_cat/templates API" when {
          "there is no index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_*")
                )

                val templates = dev1CatManager.templates("temp1")

                templates should have statusCode 200
                templates.results.map(_("name").str) should be(Vector("temp1"))
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_*")
                )

                val templates = dev1CatManager.templates("temp1")

                templates should have statusCode 200
                templates.results.map(_("name").str) should be(Vector("temp1"))
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_index_test")
                )

                val templates = dev1CatManager.templates("temp1")

                templates should have statusCode 200
                templates.results.map(_("name").str) should be(Vector("temp1"))
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_index")
                )

                val templates = dev1CatManager.templates("temp1")

                templates should have statusCode 200
                templates.results.map(_("name").str) should be(Vector("temp1"))
              }
            }
          }
          "there is an index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_*")
                )
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val templates = dev1CatManager.templates("temp1")

                templates should have statusCode 200
                templates.results.map(_("name").str) should be(Vector("temp1"))
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_*")
                )
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val templates = dev1CatManager.templates("temp1")

                templates should have statusCode 200
                templates.results.map(_("name").str) should be(Vector("temp1"))
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_index_test")
                )
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val templates = dev1CatManager.templates("temp1")

                templates should have statusCode 200
                templates.results.map(_("name").str) should be(Vector("temp1"))
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_index")
                )
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val templates = dev1CatManager.templates("temp1")

                templates should have statusCode 200
                templates.results.map(_("name").str) should be(Vector("temp1"))
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
                  adminTemplateManager.putTemplateAndWaitForIndexing(
                    templateName = "temp1",
                    indexPatterns = NonEmptyList.of("custom_dev1_*")
                  )

                  val dev1ClusterStateManager = new CatManager(basicAuthClient("dev2", "test"), esVersion = esVersionUsed)
                  val templates = dev1ClusterStateManager.templates()

                  templates should have statusCode 200
                  templates.results.map(_("name").str) should be(Vector.empty)
                }
                "rule has index pattern with no wildcard" in {
                  adminTemplateManager.putTemplateAndWaitForIndexing(
                    templateName = "temp1",
                    indexPatterns = NonEmptyList.of("dev1_*")
                  )

                  val dev1ClusterStateManager = new CatManager(basicAuthClient("dev2", "test"), esVersion = esVersionUsed)
                  val templates = dev1ClusterStateManager.templates()

                  templates should have statusCode 200
                  templates.results.map(_("name").str) should be(Vector.empty)
                }
              }
              "template has index pattern with no wildcard" when {
                "rule has index pattern with wildcard" in {
                  adminTemplateManager.putTemplateAndWaitForIndexing(
                    templateName = "temp1",
                    indexPatterns = NonEmptyList.of("custom_dev1_index_test")
                  )

                  val dev1ClusterStateManager = new CatManager(basicAuthClient("dev2", "test"), esVersion = esVersionUsed)
                  val templates = dev1ClusterStateManager.templates()

                  templates should have statusCode 200
                  templates.results.map(_("name").str) should be(Vector.empty)
                }
                "rule has index pattern with no wildcard" in {
                  adminTemplateManager.putTemplateAndWaitForIndexing(
                    templateName = "temp1",
                    indexPatterns = NonEmptyList.of("dev1_index")
                  )

                  val dev1ClusterStateManager = new CatManager(basicAuthClient("dev2", "test"), esVersion = esVersionUsed)
                  val templates = dev1ClusterStateManager.templates()

                  templates should have statusCode 200
                  templates.results.map(_("name").str) should be(Vector.empty)
                }
              }
            }
            "there is an index defined for it" when {
              "template has index pattern with wildcard" when {
                "rule has index pattern with wildcard" in {
                  adminTemplateManager.putTemplateAndWaitForIndexing(
                    templateName = "temp1",
                    indexPatterns = NonEmptyList.of("custom_dev1_*")
                  )
                  createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                  val templates = dev2CatManager.templates()

                  templates should have statusCode 200
                  templates.results.map(_("name").str) should be(Vector.empty)
                }
                "rule has index pattern with no wildcard" in {
                  adminTemplateManager.putTemplateAndWaitForIndexing(
                    templateName = "temp1",
                    indexPatterns = NonEmptyList.of("dev1_*")
                  )
                  createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                  val templates = dev2CatManager.templates()

                  templates should have statusCode 200
                  templates.results.map(_("name").str) should be(Vector.empty)
                }
              }
              "template has index pattern with no wildcard" when {
                "rule has index pattern with wildcard" in {
                  adminTemplateManager.putTemplateAndWaitForIndexing(
                    templateName = "temp1",
                    indexPatterns = NonEmptyList.of("custom_dev1_index_test")
                  )
                  createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                  val templates = dev2CatManager.templates()

                  templates should have statusCode 200
                  templates.results.map(_("name").str) should be(Vector.empty)
                }
                "rule has index pattern with no wildcard" in {
                  adminTemplateManager.putTemplateAndWaitForIndexing(
                    templateName = "temp1",
                    indexPatterns = NonEmptyList.of("dev1_index")
                  )
                  createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                  val templates = dev2CatManager.templates()

                  templates should have statusCode 200
                  templates.results.map(_("name").str) should be(Vector.empty)
                }
              }
            }
          }
        }
        "not be able to get specific, foreign template using /_cat/templates API" when {
          "there is no index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_*")
                )

                val dev1ClusterStateManager = new CatManager(basicAuthClient("dev2", "test"), esVersion = esVersionUsed)
                val templates = dev1ClusterStateManager.templates("temp1")

                templates should have statusCode 200
                templates.results.map(_("name").str) should be(Vector.empty)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_*")
                )

                val dev1ClusterStateManager = new CatManager(basicAuthClient("dev2", "test"), esVersion = esVersionUsed)
                val templates = dev1ClusterStateManager.templates("temp1")

                templates should have statusCode 200
                templates.results.map(_("name").str) should be(Vector.empty)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_index_test")
                )

                val dev1ClusterStateManager = new CatManager(basicAuthClient("dev2", "test"), esVersion = esVersionUsed)
                val templates = dev1ClusterStateManager.templates("temp1")

                templates should have statusCode 200
                templates.results.map(_("name").str) should be(Vector.empty)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_index")
                )

                val dev1ClusterStateManager = new CatManager(basicAuthClient("dev2", "test"), esVersion = esVersionUsed)
                val templates = dev1ClusterStateManager.templates("temp1")

                templates should have statusCode 200
                templates.results.map(_("name").str) should be(Vector.empty)
              }
            }
          }
          "there is an index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_*")
                )
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val dev1ClusterStateManager = new CatManager(basicAuthClient("dev2", "test"), esVersion = esVersionUsed)
                val templates = dev1ClusterStateManager.templates("temp1")

                templates should have statusCode 200
                templates.results.map(_("name").str) should be(Vector.empty)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_*")
                )
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val dev1ClusterStateManager = new CatManager(basicAuthClient("dev2", "test"), esVersion = esVersionUsed)
                val templates = dev1ClusterStateManager.templates("temp1")

                templates should have statusCode 200
                templates.results.map(_("name").str) should be(Vector.empty)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_index_test")
                )
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val dev1ClusterStateManager = new CatManager(basicAuthClient("dev2", "test"), esVersion = esVersionUsed)
                val templates = dev1ClusterStateManager.templates("temp1")

                templates should have statusCode 200
                templates.results.map(_("name").str) should be(Vector.empty)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_index")
                )
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val dev1ClusterStateManager = new CatManager(basicAuthClient("dev2", "test"), esVersion = esVersionUsed)
                val templates = dev1ClusterStateManager.templates("temp1")

                templates should have statusCode 200
                templates.results.map(_("name").str) should be(Vector.empty)
              }
            }
          }
        }
      }
    }
  }
}
