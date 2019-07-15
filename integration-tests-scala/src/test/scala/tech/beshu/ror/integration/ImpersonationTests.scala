package tech.beshu.ror.integration

import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest.WordSpec
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.DocumentManager

class ImpersonationTests extends WordSpec with ForAllTestContainer {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/impersonation/readonlyrest.yml",
    numberOfInstances = 1,
    ImpersonationTests.nodeDataInitializer()
  )

  "Impersonation can be done" when {
    "user uses local auth rule" when {
      "impersonator can be properly authenticated" in {

      }
    }
  }
  "Impersonator can use authentication method" which {
    "is local authentication" in {

    }
    "is LDAP authentication" in {

    }
    "is external authentication" in {

    }
    "is proxy authentication" in {

    }
    "is JWT token authentication" in {

    }
    "is Kibana authentication" in {

    }
  }
  "Impersonation cannot be done" when {
    "there is no such user with admin privileges" in {

    }
    "user with admin privileges cannot be authenticated" in {

    }
    "admin user is authenticated but cannot impersonate given user" in {

    }
    "not supported authentication rule is used" which {
      "is LDAP authentication" in {

      }
      "external authentication" in {

      }
      "proxy authentication" in {

      }
      "JWT token authentication" in {

      }
      "Kibana authentication" in {

      }
    }
  }
}

object ImpersonationTests {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (documentManager: DocumentManager) => {
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
  }
}