package tech.beshu.ror.integration

import java.time.Clock

import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.Acl
import tech.beshu.ror.acl.factory.{AsyncHttpClientsFactory, CoreFactory, CoreSettings}
import tech.beshu.ror.acl.utils.{JavaEnvVarsProvider, JavaUuidProvider, StaticVariablesResolver, UuidProvider}
import tech.beshu.ror.utils.TestsUtils.BlockContextAssertion

class OneTest  extends WordSpec with MockFactory with Inside with BlockContextAssertion {
  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val resolver: StaticVariablesResolver = new StaticVariablesResolver(JavaEnvVarsProvider)
    new CoreFactory
  }
  private val acl: Acl = factory
    .createCoreFrom(
      """
        |readonlyrest:
        |  enable: true
        |  response_if_req_forbidden: Forbidden by ReadonlyREST plugin
        |  ssl:
        |    enable: true
        |    keystore_file: "elasticsearch.jks"
        |    keystore_pass: "pass"
        |    key_pass: "pass"
        |  access_control_rules:
        |    # LOCAL: Kibana admin account
        |    - name: "local-admin"
        |      auth_key: "admin:pass"
        |      kibana_access: admin
        |    # LOCAL: Logstash servers inbound access
        |    - name: "local-logstash"
        |      auth_key: "logstash:pass"
        |      # Local accounts for routine access should have less verbisity
        |      #  to keep the amount of logfile noise down
        |      verbosity: error
        |    # LOCAL: Kibana server
        |    - name: "local-kibana"
        |      auth_key: "kibana:pass"
        |      verbosity: error
        |    # LOCAL: Puppet communication
        |    - name: "local-puppet"
        |      auth_key: "puppet:pass"
        |      verbosity: error
        |    # LOCAL: Elastalert
        |    - name: "elastalert"
        |      auth_key: "elastalert:pass"
        |      verbosity: error
        |    # LDAP: kibana-admin group
        |    - name: "ldap-admin"
        |      kibana_access: admin
        |      kibana_hide_apps: [""]
        |      type: allow
        |    # LDAP for everyone else
        |    - name: "ldap-all"
        |      # possibly include: "kibana:dev_tools",
        |      kibana_hide_apps: ["readonlyrest_kbn", "timelion", "kibana:management", "apm"]
        |      type: allow
        |    # Allow localhost
        |    - name: "localhost"
        |      hosts: ["127.0.0.1"]
        |  # Define the LDAP connection
    """.stripMargin,
      new AsyncHttpClientsFactory
    )
    .map {
      case Left(err) => throw new IllegalStateException(s"Cannot create ACL: $err")
      case Right(CoreSettings(aclEngine, _, _)) => aclEngine
    }
    .runSyncUnsafe()

  "A ACL" in {
    val i = 0
  }
}
