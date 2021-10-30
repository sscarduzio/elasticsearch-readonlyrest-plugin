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
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.EsContainerCreator
import tech.beshu.ror.utils.elasticsearch.BaseTemplateManager.Template
import tech.beshu.ror.utils.elasticsearch.ComponentTemplateManager.ComponentTemplate
import tech.beshu.ror.utils.elasticsearch.{BaseTemplateManager, ComponentTemplateManager, IndexTemplateManager, LegacyTemplateManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait IndexTemplatesManagementSuite
  extends AnyWordSpec
    with BaseTemplatesSuite
    with ESVersionSupportForAnyWordSpecLike {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/templates/readonlyrest.yml"

  indexTemplateApiTests("A legacy template API")(new LegacyTemplateManager(_, esVersionUsed))
  if (doesSupportIndexTemplates) indexTemplateApiTests("A new template API")(new IndexTemplateManager(_, esVersionUsed))
  if (doesSupportComponentTemplates) componentTemplateApiTests()
  if (doesSupportIndexTemplates) simulateTemplatesApiTests()

  def indexTemplateApiTests(name: String)
                           (templateManagerCreator: RestClient => BaseTemplateManager): Unit = {

    lazy val dev1TemplateManager = templateManagerCreator(basicAuthClient("dev1", "test"))
    lazy val dev2TemplateManager = templateManagerCreator(basicAuthClient("dev2", "test"))
    lazy val dev3TemplateManager = templateManagerCreator(basicAuthClient("dev3", "test"))
    lazy val adminTemplateManager = templateManagerCreator(adminClient)

    s"$name" when {
      "user is dev1" should {
        "see empty list of templates" when {
          "there is none" in {
            val templates = dev1TemplateManager.getTemplates

            templates.responseCode should be(200)
            templates.templates should be(List.empty)
          }
        }
        "be allowed to get all templates" when {
          "there is no index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.one("custom_dev1_*"),
                  aliases = Set("dev1_index")
                )

                val templates = dev1TemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.templates should be(List(Template("temp1", Set("custom_dev1_*"), Set("dev1_index"))))
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.one("dev1_*"),
                  aliases = Set.empty
                )

                val templates = dev1TemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.templates should be(List(Template("temp1", Set("dev1_*"), Set.empty)))
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.one("custom_dev1_index_test"),
                  aliases = Set.empty
                )

                val templates = dev1TemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.templates should be(List(Template("temp1", Set("custom_dev1_index_test"), Set.empty)))
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.one("dev1_index"),
                  aliases = Set.empty
                )

                val templates = dev1TemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.templates should be(List(Template("temp1", Set("dev1_index"), Set.empty)))
              }
            }
          }
          "there is an index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.one("custom_dev1_*"),
                  aliases = Set.empty
                )

                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val templates = dev1TemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.templates should be(List(Template("temp1", Set("custom_dev1_*"), Set.empty)))
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.one("dev1_*"),
                  aliases = Set.empty
                )

                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val templates = dev1TemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.templates should be(List(Template("temp1", Set("dev1_*"), Set.empty)))
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.one("custom_dev1_index_test"),
                  aliases = Set.empty
                )

                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val templates = dev1TemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.templates should be(List(Template("temp1", Set("custom_dev1_index_test"), Set.empty)))
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.one("dev1_index"),
                  aliases = Set.empty
                )

                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val templates = dev1TemplateManager.getTemplates

                templates.responseCode should be(200)
                templates.templates should be(List(Template("temp1", Set("dev1_index"), Set.empty)))
              }
            }
          }
        }
        "be allowed to get a specific template" when {
          "there is no index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.one("custom_dev1_*"),
                  aliases = Set.empty
                )

                val templates = dev1TemplateManager.getTemplate("temp1")

                templates.responseCode should be(200)
                templates.templates should be(List(Template("temp1", Set("custom_dev1_*"), Set.empty)))
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.one("dev1_*"),
                  aliases = Set.empty
                )

                val templates = dev1TemplateManager.getTemplate("temp1")

                templates.responseCode should be(200)
                templates.templates should be(List(Template("temp1", Set("dev1_*"), Set.empty)))
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.one("custom_dev1_index_test"),
                  aliases = Set.empty
                )

                val templates = dev1TemplateManager.getTemplate("temp1")

                templates.responseCode should be(200)
                templates.templates should be(List(Template("temp1", Set("custom_dev1_index_test"), Set.empty)))
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.one("dev1_index"),
                  aliases = Set.empty
                )

                val templates = dev1TemplateManager.getTemplate("temp1")

                templates.responseCode should be(200)
                templates.templates should be(List(Template("temp1", Set("dev1_index"), Set.empty)))
              }
            }
          }
          "there is an index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.one("custom_dev1_*"),
                  aliases = Set.empty
                )
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val templates = dev1TemplateManager.getTemplate("temp1")

                templates.responseCode should be(200)
                templates.templates should be(List(Template("temp1", Set("custom_dev1_*"), Set.empty)))
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.one("dev1_*"),
                  aliases = Set.empty
                )
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val templates = dev1TemplateManager.getTemplate("temp1")

                templates.responseCode should be(200)
                templates.templates should be(List(Template("temp1", Set("dev1_*"), Set.empty)))
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.one("custom_dev1_index_test"),
                  aliases = Set.empty
                )
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val templates = dev1TemplateManager.getTemplate("temp1")

                templates.responseCode should be(200)
                templates.templates should be(List(Template("temp1", Set("custom_dev1_index_test"), Set.empty)))
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.one("dev1_index"),
                  aliases = Set.empty
                )
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val templates = dev1TemplateManager.getTemplate("temp1")

                templates.responseCode should be(200)
                templates.templates should be(List(Template("temp1", Set("dev1_index"), Set.empty)))
              }
            }
          }
          "with filtered not allowed pattern and alias" in {
            adminTemplateManager.putTemplateAndWaitForIndexing(
              templateName = "temp1",
              indexPatterns = NonEmptyList.one("custom_dev1_*"),
              aliases = Set("not_allowed_alias", "dev1_index")
            )

            val templates = dev1TemplateManager.getTemplate("temp1")

            templates.responseCode should be(200)
            templates.templates should be(List(Template("temp1", Set("custom_dev1_*"), Set("dev1_index"))))
          }
          "at least one template index pattern matches user's allowed indices" excludeES (allEs5x) in {
            adminTemplateManager.putTemplateAndWaitForIndexing(
              templateName = "temp1",
              indexPatterns = NonEmptyList.of("custom_dev1_*", "custom_dev2_*"),
              aliases = Set.empty
            )
            createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")
            createIndexWithExampleDoc(adminDocumentManager, "custom_dev2_index_test")

            val templates = dev1TemplateManager.getTemplate("temp1")

            templates.responseCode should be(200)
            templates.templates should be(List(Template("temp1", Set("custom_dev1_*"), Set.empty)))
          }
        }
        "be allowed to create a new template" when {
          "there is no index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                val result = dev1TemplateManager.putTemplate(
                  templateName = "new_template",
                  indexPatterns = NonEmptyList.one("custom_dev1_index_*"),
                  aliases = Set("dev1_index")
                )

                result.responseCode should be(200)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                val result = dev1TemplateManager.putTemplate(
                  templateName = "new_template",
                  indexPatterns = NonEmptyList.one("custom_dev1_index_test"),
                  aliases = Set.empty
                )

                result.responseCode should be(200)
              }
              "rule has index pattern with no wildcard" in {
                val result = dev1TemplateManager.putTemplate(
                  templateName = "new_template",
                  indexPatterns = NonEmptyList.one("dev1_index"),
                  aliases = Set.empty
                )

                result.responseCode should be(200)
              }
            }
          }
          "there is an index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminDocumentManager.createFirstDoc("custom_dev1_index_test", ujson.read("""{"hello":"world"}"""))

                val result = dev1TemplateManager.putTemplate(
                  templateName = "new_template",
                  indexPatterns = NonEmptyList.one("custom_dev1_index_*"),
                  aliases = Set.empty
                )

                result.responseCode should be(200)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminDocumentManager.createFirstDoc("custom_dev1_index_test", ujson.read("""{"hello":"world"}"""))

                val result = dev1TemplateManager.putTemplate(
                  templateName = "new_template",
                  indexPatterns = NonEmptyList.one("custom_dev1_index_test"),
                  aliases = Set.empty
                )

                result.responseCode should be(200)
              }
              "rule has index pattern with no wildcard" in {
                adminDocumentManager.createFirstDoc("dev1_index", ujson.read("""{"hello":"world"}"""))

                val result = dev1TemplateManager.putTemplate(
                  templateName = "new_template",
                  indexPatterns = NonEmptyList.one("dev1_index"),
                  aliases = Set("custom_dev1_index_test")
                )

                result.responseCode should be(200)
              }
            }
          }
          "template applies to generic index pattern (ES >= 6.0.0)" excludeES (allEs5x) in {
            val result = dev1TemplateManager.putTemplate(
              templateName = "new_template",
              indexPatterns = NonEmptyList.one("custom_dev1_index_*"),
              aliases = Set.empty
            )

            result.responseCode should be(200)

            val user1Template = adminTemplateManager.getTemplate("new_template")
            user1Template.responseCode should be(200)
            user1Template.templates should be(List(Template("new_template", Set("custom_dev1_index_*"), Set.empty)))
          }
          "template applies to generic index pattern (ES < 6.0.0)" excludeES(allEs6x, allEs7x, rorProxy) in {
            val result = dev1TemplateManager.putTemplate(
              templateName = "new_template",
              indexPatterns = NonEmptyList.one("custom_dev1_index_*"),
              aliases = Set.empty
            )

            result.responseCode should be(200)

            val user1Template = adminTemplateManager.getTemplate("new_template")
            user1Template.responseCode should be(200)
            user1Template.templates should be(List(Template("new_template", Set("custom_dev1_index_*"), Set.empty)))
          }
        }
        "be allowed to override an existing template" which {
          "belongs to him" in {
            val insert1Result = dev1TemplateManager.putTemplate(
              templateName = "new_template",
              indexPatterns = NonEmptyList.one("custom_dev1_index_test*"),
              aliases = Set.empty
            )
            insert1Result.responseCode should be(200)

            val user1Template = dev1TemplateManager.getTemplates
            user1Template.responseCode should be(200)
            user1Template.templates should be(List(Template("new_template", Set("custom_dev1_index_test*"), Set.empty)))

            val insert2Result = dev1TemplateManager.putTemplate(
              templateName = "new_template",
              indexPatterns = NonEmptyList.one("dev1_index"),
              aliases = Set.empty
            )
            insert2Result.responseCode should be(200)

            val user1TemplateAfterOverride = dev1TemplateManager.getTemplates
            user1TemplateAfterOverride.responseCode should be(200)
            user1TemplateAfterOverride.templates should be(List(Template("new_template", Set("dev1_index"), Set.empty)))
          }
        }
        "be allowed to remove his template" when {
          "there is no index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp_to_remove",
                  indexPatterns = NonEmptyList.one("custom_dev1_index_*"),
                  aliases = Set("dev1_index")
                )

                val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

                result.responseCode should be(200)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp_to_remove",
                  indexPatterns = NonEmptyList.one("dev1_index"),
                  aliases = Set.empty
                )

                val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

                result.responseCode should be(200)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp_to_remove",
                  indexPatterns = NonEmptyList.one("custom_dev1_index_test"),
                  aliases = Set.empty
                )

                val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

                result.responseCode should be(200)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp_to_remove",
                  indexPatterns = NonEmptyList.one("dev1_index"),
                  aliases = Set.empty
                )

                val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

                result.responseCode should be(200)
              }
            }
          }
          "there is an index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp_to_remove",
                  indexPatterns = NonEmptyList.one("custom_dev1_index_*"),
                  aliases = Set("dev1_index")
                )
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

                result.responseCode should be(200)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp_to_remove",
                  indexPatterns = NonEmptyList.one("dev1_index"),
                  aliases = Set.empty
                )
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

                result.responseCode should be(200)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp_to_remove",
                  indexPatterns = NonEmptyList.one("custom_dev1_index_test"),
                  aliases = Set.empty
                )
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

                result.responseCode should be(200)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp_to_remove",
                  indexPatterns = NonEmptyList.one("dev1_index"),
                  aliases = Set.empty
                )
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val result = dev1TemplateManager.deleteTemplate("temp_to_remove")

                result.responseCode should be(200)
              }
            }
          }
          "all indices patterns defined in template are allowed to the user" excludeES (allEs5x) in {
            adminTemplateManager.putTemplateAndWaitForIndexing(
              templateName = "temp",
              indexPatterns = NonEmptyList.of("custom_dev1_index_*", "dev1_index"),
              aliases = Set.empty
            )

            val result = dev1TemplateManager.deleteTemplate("temp")

            result.responseCode should be(200)
          }
          "index pattern defined in template is allowed to the user" in {
            adminTemplateManager.putTemplateAndWaitForIndexing(
              templateName = "temp",
              indexPatterns = NonEmptyList.of("custom_dev1_index_*"),
              aliases = Set.empty
            )

            val result = dev1TemplateManager.deleteTemplate("temp")

            result.responseCode should be(200)
          }
        }
      }
      "user is dev2" should {
        "not be able to get templates" when {
          "there are no his templates but other user's one exists" when {
            "there is no index defined for it" when {
              "template has index pattern with wildcard" when {
                "rule has index pattern with wildcard" in {
                  adminTemplateManager.putTemplateAndWaitForIndexing(
                    templateName = "temp1",
                    indexPatterns = NonEmptyList.of("custom_dev1_*"),
                    aliases = Set.empty
                  )

                  val templates = dev2TemplateManager.getTemplates

                  templates.responseCode should be(200)
                  templates.templates should be(List.empty)
                }
                "rule has index pattern with no wildcard" in {
                  adminTemplateManager.putTemplateAndWaitForIndexing(
                    templateName = "temp1",
                    indexPatterns = NonEmptyList.of("dev1_*"),
                    aliases = Set.empty
                  )

                  val templates = dev2TemplateManager.getTemplates

                  templates.responseCode should be(200)
                  templates.templates should be(List.empty)
                }
              }
              "template has index pattern with no wildcard" when {
                "rule has index pattern with wildcard" in {
                  adminTemplateManager.putTemplateAndWaitForIndexing(
                    templateName = "temp1",
                    indexPatterns = NonEmptyList.of("custom_dev1_index_test"),
                    aliases = Set.empty
                  )

                  val templates = dev2TemplateManager.getTemplates

                  templates.responseCode should be(200)
                  templates.templates should be(List.empty)
                }
                "rule has index pattern with no wildcard" in {
                  adminTemplateManager.putTemplateAndWaitForIndexing(
                    templateName = "temp1",
                    indexPatterns = NonEmptyList.of("dev1_index"),
                    aliases = Set.empty
                  )

                  val templates = dev2TemplateManager.getTemplates

                  templates.responseCode should be(200)
                  templates.templates should be(List.empty)
                }
              }
            }
            "there is an index defined for it" when {
              "template has index pattern with wildcard" when {
                "rule has index pattern with wildcard" in {
                  adminTemplateManager.putTemplateAndWaitForIndexing(
                    templateName = "temp1",
                    indexPatterns = NonEmptyList.of("custom_dev1_*"),
                    aliases = Set.empty
                  )
                  createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                  val templates = dev2TemplateManager.getTemplates

                  templates.responseCode should be(200)
                  templates.templates should be(List.empty)
                }
                "rule has index pattern with no wildcard" in {
                  adminTemplateManager.putTemplateAndWaitForIndexing(
                    templateName = "temp1",
                    indexPatterns = NonEmptyList.of("dev1_*"),
                    aliases = Set.empty
                  )
                  createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                  val templates = dev2TemplateManager.getTemplates

                  templates.responseCode should be(200)
                  templates.templates should be(List.empty)
                }
              }
              "template has index pattern with no wildcard" when {
                "rule has index pattern with wildcard" in {
                  adminTemplateManager.putTemplateAndWaitForIndexing(
                    templateName = "temp1",
                    indexPatterns = NonEmptyList.of("custom_dev1_index_test"),
                    aliases = Set.empty
                  )
                  createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                  val templates = dev2TemplateManager.getTemplates

                  templates.responseCode should be(200)
                  templates.templates should be(List.empty)
                }
                "rule has index pattern with no wildcard" in {
                  adminTemplateManager.putTemplateAndWaitForIndexing(
                    templateName = "temp1",
                    indexPatterns = NonEmptyList.of("dev1_index"),
                    aliases = Set.empty
                  )
                  createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                  val templates = dev2TemplateManager.getTemplates

                  templates.responseCode should be(200)
                  templates.templates should be(List.empty)
                }
              }
            }
          }
        }
        "not be able to get specific, foreign template" when {
          "there is no index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_*"),
                  aliases = Set.empty
                )

                val templates = dev2TemplateManager.getTemplate("temp1")

                templates.responseCode should be(404)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_*"),
                  aliases = Set.empty
                )

                val templates = dev2TemplateManager.getTemplate("temp1")

                templates.responseCode should be(404)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_index_test"),
                  aliases = Set.empty
                )

                val templates = dev2TemplateManager.getTemplate("temp1")

                templates.responseCode should be(404)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_index"),
                  aliases = Set.empty
                )

                val templates = dev2TemplateManager.getTemplate("temp1")

                templates.responseCode should be(404)
              }
            }
          }
          "there is an index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_*"),
                  aliases = Set.empty
                )
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val templates = dev2TemplateManager.getTemplate("temp1")

                templates.responseCode should be(404)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_*"),
                  aliases = Set.empty
                )
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val templates = dev2TemplateManager.getTemplate("temp1")

                templates.responseCode should be(404)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_index_test"),
                  aliases = Set.empty
                )
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val templates = dev2TemplateManager.getTemplate("temp1")

                templates.responseCode should be(404)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_index"),
                  aliases = Set.empty
                )
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
              val result = dev2TemplateManager.putTemplate(
                templateName = "new_template",
                indexPatterns = NonEmptyList.of("custom_dev1_*"),
                aliases = Set.empty
              )

              result.responseCode should be(401)
            }
            "rule has index pattern with no wildcard" in {
              val result = dev2TemplateManager.putTemplate(
                templateName = "new_template",
                indexPatterns = NonEmptyList.of("dev1_index*"),
                aliases = Set.empty
              )

              result.responseCode should be(401)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              val result = dev2TemplateManager.putTemplate(
                templateName = "new_template",
                indexPatterns = NonEmptyList.of("custom_dev1_index_test"),
                aliases = Set.empty
              )

              result.responseCode should be(401)
            }
            "rule has index pattern with no wildcard" in {
              val result = dev2TemplateManager.putTemplate(
                templateName = "new_template",
                indexPatterns = NonEmptyList.of("dev1_index"),
                aliases = Set.empty
              )

              result.responseCode should be(401)
            }
          }
          "template contains only not allowed index patterns" in {
            val result = dev2TemplateManager.putTemplate(
              templateName = "new_template",
              indexPatterns = NonEmptyList.of("custom_dev1_index_1*"),
              aliases = Set.empty
            )

            result.responseCode should be(401)
          }
          "template contains only not allowed aliases" in {
            val result = dev2TemplateManager.putTemplate(
              templateName = "new_template",
              indexPatterns = NonEmptyList.of("custom_dev2_index_1*"),
              aliases = Set("dev1_index")
            )

            result.responseCode should be(401)
          }
          "template applies to allowed index and not allowed index patterns" excludeES (allEs5x) in {
            val result = dev2TemplateManager.putTemplate(
              templateName = "new_template",
              indexPatterns = NonEmptyList.of("custom_dev1_index_*"),
              aliases = Set.empty
            )

            result.responseCode should be(401)
          }
        }
        "not be able to override existing template" which {
          "doesn't belong to him (not allowed patterns)" in {
            val result1 = dev1TemplateManager.putTemplate(
              templateName = "new_template",
              indexPatterns = NonEmptyList.of("custom_dev1_index_*"),
              aliases = Set.empty
            )
            result1.responseCode should be(200)

            val result2 = dev2TemplateManager.putTemplate(
              templateName = "new_template",
              indexPatterns = NonEmptyList.of("custom_dev2_index_*"),
              aliases = Set.empty
            )
            result2.responseCode should be(401)
          }
          "doesn't belong to him (not allowed aliases)" in {
            adminTemplateManager.putTemplateAndWaitForIndexing(
              templateName = "new_template",
              indexPatterns = NonEmptyList.of("custom_dev2_index_*"),
              aliases = Set("dev1_index")
            )

            val result = dev2TemplateManager.putTemplate(
              templateName = "new_template",
              indexPatterns = NonEmptyList.of("dev2_index"),
              aliases = Set.empty
            )
            result.responseCode should be(401)
          }
        }
        "not be able to delete foreign template" when {
          "there is no index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_*"),
                  aliases = Set.empty
                )

                val result = dev2TemplateManager.deleteTemplate("temp1")

                result.responseCode should be(401)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_*"),
                  aliases = Set.empty
                )

                val result = dev2TemplateManager.deleteTemplate("temp1")

                result.responseCode should be(401)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_index_test"),
                  aliases = Set.empty
                )

                val result = dev2TemplateManager.deleteTemplate("temp1")

                result.responseCode should be(401)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_index"),
                  aliases = Set.empty
                )

                val result = dev2TemplateManager.deleteTemplate("temp1")

                result.responseCode should be(401)
              }
            }
          }
          "there is an index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_*"),
                  aliases = Set.empty
                )
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val result = dev2TemplateManager.deleteTemplate("temp1")

                result.responseCode should be(401)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_*"),
                  aliases = Set.empty
                )
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val result = dev2TemplateManager.deleteTemplate("temp1")

                result.responseCode should be(401)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("custom_dev1_index_test"),
                  aliases = Set.empty
                )
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val result = dev2TemplateManager.deleteTemplate("temp1")

                result.responseCode should be(401)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.putTemplateAndWaitForIndexing(
                  templateName = "temp1",
                  indexPatterns = NonEmptyList.of("dev1_index"),
                  aliases = Set.empty
                )
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val result = dev2TemplateManager.deleteTemplate("temp1")

                result.responseCode should be(401)
              }
            }
          }
          "not all index patterns defined in template are allowed to the user" excludeES (allEs5x) in {
            adminTemplateManager.putTemplateAndWaitForIndexing(
              templateName = "temp",
              indexPatterns = NonEmptyList.of("custom_dev1_index_*", "custom_dev2_index_*"),
              aliases = Set.empty
            )

            val result = dev2TemplateManager.deleteTemplate("temp")

            result.responseCode should be(401)
          }
          "index pattern defined in template are too generic" in {
            adminTemplateManager.putTemplateAndWaitForIndexing(
              templateName = "temp",
              indexPatterns = NonEmptyList.of("dev*"),
              aliases = Set.empty
            )

            val result = dev2TemplateManager.deleteTemplate("temp")

            result.responseCode should be(401)
          }
          "one of aliases is not allowed" in {
            adminTemplateManager.putTemplateAndWaitForIndexing(
              templateName = "temp",
              indexPatterns = NonEmptyList.of("dev2_index"),
              aliases = Set("dev1_index")
            )

            val result = dev2TemplateManager.deleteTemplate("temp1")

            result.responseCode should be(404)
          }
        }
      }
      "user is dev3" should {
        "be allowed to remove his template" when {
          "he previously added it (because it was allowed by block with no indices rule)" in {
            val addingResult = dev3TemplateManager.putTemplate(
              templateName = "new_template",
              indexPatterns = NonEmptyList.of("any_index_*")
            )
            addingResult.responseCode should be(200)

            val deletingResult = dev3TemplateManager.deleteTemplate("new_template")
            deletingResult.responseCode should be(200)
          }
        }
      }
    }
  }

  def componentTemplateApiTests(): Unit = {

    lazy val dev1TemplateManager = new ComponentTemplateManager(basicAuthClient("dev1", "test"), esVersionUsed)
    lazy val dev2TemplateManager = new ComponentTemplateManager(basicAuthClient("dev2", "test"), esVersionUsed)
    lazy val adminTemplateManager = new ComponentTemplateManager(adminClient, esVersionUsed)

    "A component template API" when {
      "user is dev1" should {
        "be allowed to get all user templates" in {
          adminTemplateManager.putTemplateAndWaitForIndexing("temp1", Set.empty)
          adminTemplateManager.putTemplateAndWaitForIndexing("temp2", Set("dev1_index"))
          adminTemplateManager.putTemplateAndWaitForIndexing("temp3", Set("dev2_index"))
          adminTemplateManager.putTemplateAndWaitForIndexing("temp4", Set("dev1_index", "dev2_index"))

          val result = dev1TemplateManager.getTemplates

          result.responseCode should be(200)
          result.templates should contain allOf(
            ComponentTemplate("temp1", Set.empty),
            ComponentTemplate("temp2", Set("dev1_index")),
            ComponentTemplate("temp3", Set.empty),
            ComponentTemplate("temp4", Set("dev1_index"))
          )
        }
        "be allowed to get a specific template" when {
          "full name template is used" in {
            adminTemplateManager.putTemplateAndWaitForIndexing("temp1", Set.empty)
            adminTemplateManager.putTemplateAndWaitForIndexing("temp2", Set("dev1_index"))

            val result = dev1TemplateManager.getTemplate("temp2")

            result.responseCode should be(200)
            result.templates should contain(
              ComponentTemplate("temp2", Set("dev1_index"))
            )
          }
          "wildcard is used" in {
            adminTemplateManager.putTemplateAndWaitForIndexing("temp1", Set("dev1_index"))
            adminTemplateManager.putTemplateAndWaitForIndexing("temp2", Set("dev2_index"))

            val result = dev1TemplateManager.getTemplate("temp*")

            result.responseCode should be(200)
            result.templates should contain allOf(
              ComponentTemplate("temp1", Set("dev1_index")),
              ComponentTemplate("temp2", Set.empty)
            )
          }
        }
        "be allowed to create a new template" when {
          "the template has no aliases" in {
            val result = dev1TemplateManager.putTemplate("temp1", Set.empty)

            result.responseCode should be(200)
          }
          "the template has aliases and the user has access to them" in {
            val result = dev1TemplateManager.putTemplate("temp1", Set("dev1_index", "custom_dev1_index_1"))

            result.responseCode should be(200)
          }
        }
        "be allowed to override an existing template" when {
          "the existing template has no aliases" in {
            adminTemplateManager.putTemplateAndWaitForIndexing("temp1", Set.empty)

            val result = dev1TemplateManager.putTemplate("temp1", Set("dev1_index"))

            result.responseCode should be(200)
          }
          "the existing template has only aliases which are allowed" in {
            adminTemplateManager.putTemplateAndWaitForIndexing("temp1", Set("dev1_index", "custom_dev1_index_1"))

            val result = dev1TemplateManager.putTemplate("temp1", Set("custom_dev1_index_2"))

            result.responseCode should be(200)
          }
        }
        "be allowed to remove a template" when {
          "the template has no aliases" in {
            adminTemplateManager.putTemplateAndWaitForIndexing("temp1", Set.empty)

            val result = dev1TemplateManager.deleteTemplate("temp1")

            result.responseCode should be(200)
          }
          "the template has aliases which user is allowed to see" in {
            adminTemplateManager.putTemplateAndWaitForIndexing("temp1", Set("dev1_index", "custom_dev1_index_1"))

            val result = dev1TemplateManager.deleteTemplate("temp1")

            result.responseCode should be(200)
          }
        }
      }
      "user is dev2" should {
        "not be allowed to create a new template" when {
          "template has at least one non allowed alias" in {
            val result = dev2TemplateManager.putTemplate("temp1", Set("dev2_index", "custom_dev1_index_1"))

            result.responseCode should be(401)
          }
        }
        "not be allowed to override an existing template" when {
          "the existing template has alias which is forbidden for the user" in {
            adminTemplateManager.putTemplateAndWaitForIndexing("temp1", Set("dev2_index", "custom_dev1_index_1"))

            val result = dev2TemplateManager.putTemplate("temp1", Set.empty)

            result.responseCode should be(401)
          }
        }
        "not be allowed to remove a template" when {
          "the template has at least one index which user is not allowed to see" in {
            adminTemplateManager.putTemplateAndWaitForIndexing("temp1", Set("dev2_index", "custom_dev1_index_1"))

            val result = dev2TemplateManager.deleteTemplate("temp1")

            result.responseCode should be(401)
          }
        }
      }
    }
  }

  def simulateTemplatesApiTests(): Unit = {
    lazy val adminIndexTemplateManager = new IndexTemplateManager(adminClient, esVersionUsed)
    lazy val user1IndexTemplateManager = new IndexTemplateManager(basicAuthClient("dev1", "test"), esVersionUsed)

    "A simulate index API" should {
      "be allowed for a user" which {
        "has access to the given index" in {
          adminIndexTemplateManager
            .putTemplateAndWaitForIndexing(
              templateName = "temp1",
              indexPatterns = NonEmptyList.of("custom_dev1_index_*", "custom_dev2_index_*"),
              aliases = Set("dev1_index", "dev2_index"),
              priority = 4
            )
          adminIndexTemplateManager
            .putTemplateAndWaitForIndexing(
              templateName = "temp2",
              indexPatterns = NonEmptyList.of("custom*", "custom_dev2*"),
              aliases = Set.empty,
              priority = 1
            )

          val result = user1IndexTemplateManager.simulateIndex("custom_dev1_index_test")

          result.responseCode should be(200)
          result.templateAliases should be(Set("dev1_index"))
          result.overlappingTemplates should be(List(Template("temp2", Set("custom*"), Set.empty)))
        }
      }
      "not be allowed for a user" which {
        "has no access to the given index" in {
          adminIndexTemplateManager
            .putTemplateAndWaitForIndexing(
              templateName = "temp1",
              indexPatterns = NonEmptyList.of("custom_dev1_index_*", "custom_dev2_index_*"),
              aliases = Set("dev1_index", "dev2_index")
            )

          val result = user1IndexTemplateManager.simulateIndex("custom_dev2_index_test")

          result.responseCode should be(401)
        }
      }
    }

    "A simulate template API" should {
      "be allowed for a user" which {
        "has an access to the given existing template" excludeES(allEs5x, allEs6x, allEs7xBelowEs79x, rorProxy) in {
          adminIndexTemplateManager
            .putTemplateAndWaitForIndexing(
              templateName = "temp1",
              indexPatterns = NonEmptyList.of("custom_dev1_index_*", "custom_dev2_index_*"),
              aliases = Set("dev1_index", "dev2_index"),
              priority = 4
            )
          adminIndexTemplateManager
            .putTemplateAndWaitForIndexing(
              templateName = "temp2",
              indexPatterns = NonEmptyList.of("custom*", "custom_dev2*"),
              aliases = Set.empty,
              priority = 1
            )

          val result = user1IndexTemplateManager.simulateTemplate("temp1")

          result.responseCode should be(200)
          result.templateAliases should be(Set("dev1_index"))
          result.overlappingTemplates should be(List.empty)
        }
        "has an access to given non-existing template" excludeES(allEs5x, allEs6x, allEs7xBelowEs79x, rorProxy) in {
          adminIndexTemplateManager
            .putTemplateAndWaitForIndexing(
              templateName = "temp2",
              indexPatterns = NonEmptyList.of("custom*", "custom_dev2*"),
              aliases = Set("dev2_index"),
              priority = 0
            )

          val result = user1IndexTemplateManager.simulateNewTemplate(
            indexPatterns = NonEmptyList.of("custom_dev1_index_temp*"),
            aliases = Set("dev1_index")
          )

          result.responseCode should be(200)
          result.templateAliases should be(Set("dev1_index"))
          result.overlappingTemplates should be(List(
            Template("temp2", Set("custom*"), Set.empty)
          ))
        }
      }
      "not to be allowed for a user" which {
        "has no access to the given existing template" excludeES(allEs5x, allEs6x, allEs7xBelowEs79x, rorProxy) in {
          adminIndexTemplateManager
            .putTemplateAndWaitForIndexing(
              templateName = "temp1",
              indexPatterns = NonEmptyList.of("custom_dev2_index_*"),
              aliases = Set("dev2_index")
            )

          val result = user1IndexTemplateManager.simulateTemplate("temp1")

          result.responseCode should be(400)
        }
        "has no access to the given non-existing template" excludeES(allEs5x, allEs6x, allEs7xBelowEs79x, rorProxy) in {
          adminIndexTemplateManager
            .putTemplateAndWaitForIndexing(
              templateName = "temp1",
              indexPatterns = NonEmptyList.of("custom_dev2_index_*"),
              aliases = Set("dev2_index")
            )

          val result = user1IndexTemplateManager.simulateTemplate("temp1")

          result.responseCode should be(400)
        }
      }
    }
  }
}
