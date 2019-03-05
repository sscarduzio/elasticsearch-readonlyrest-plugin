package tech.beshu.ror.utils

import java.io.File

import com.dimafeng.testcontainers.GenericContainer
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionOptions}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy
import tech.beshu.ror.acl.utils.ScalaOps
import tech.beshu.ror.utils.containers.ContainerUtils

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

object LdapContainer extends Logging {
  def create(ldapInitScript: String): GenericContainer = {
    val ldapInitScriptFile = ContainerUtils.getResourceFile(ldapInitScript)
    logger.info("Creating LDAP container ...")
    new GenericContainer(
      dockerImage = "osixia/openldap:1.1.7",
      env = Map("LDAP_ORGANISATION" -> "example", "LDAP_DOMAIN" -> "example.com", "LDAP_ADMIN_PASSWORD" -> "admin"),
      exposedPorts = Seq(389),
      waitStrategy = Some(ldapWaitStrategy(ldapInitScriptFile))
    )
  }

  private def ldapWaitStrategy(initScript: File) = new AbstractWaitStrategy {
    override def waitUntilReady(): Unit = ???

    private def tryConnect(address: String, port: Int): Task[Option[LDAPConnection]] = {
      Task(createConnection)
        .bracket(connection =>
          Task(connection.connect(address, port))
        )( connection =>
          Task(connection.close())
        )
    }

    private def createConnection: LDAPConnection = {
      val options = new LDAPConnectionOptions()
      options.setConnectTimeoutMillis(Defaults.connectionTimeout.toMillis.toInt)
      new LDAPConnection(options)
    }
  }

  object Defaults {
    val connectionTimeout: FiniteDuration = 5 seconds
  }

//  return new org.testcontainers.containers.wait.strategy.AbstractWaitStrategy() {
//
//    @Override
//    protected void waitUntilReady() {
//      logger.info("Waiting for LDAP container ...");
//      Optional<LDAPConnection> connection = tryConnect(getLdapHost(), getLdapPort());
//      if (connection.isPresent()) {
//        try {
//          initLdap(connection.get(), initialDataLdif);
//        } catch (Exception e) {
//          throw new IllegalStateException(e);
//        } finally {
//          connection.get().close();
//        }
//      }
//      else {
//        throw new IllegalStateException("Cannot connect");
//      }
//      logger.info("LDAP container stated");
//    }
//
//    private LDAPConnection createConnection() {
//      LDAPConnectionOptions options = new LDAPConnectionOptions();
//      options.setConnectTimeoutMillis((int) LDAP_CONNECT_TIMEOUT.toMillis());
//      return new LDAPConnection(options);
//    }
//
//    private Optional<LDAPConnection> tryConnect(String address, Integer port) {
//      final Instant startTime = Instant.now();
//      final LDAPConnection connection = createConnection();
//      do {
//        try {
//          connection.connect(address, port);
//          Thread.sleep(WAIT_BETWEEN_RETRIES.toMillis());
//        } catch (Exception ignored) {
//        }
//      } while (!connection.isConnected() && !checkTimeout(startTime, startupTimeout));
//      return Optional.of(connection);
//    }
//
//    private void initLdap(LDAPConnection connection, File initialDataLdif) throws Exception {
//      LDAPConnection bindedConnection = bind(connection);
//      LDIFReader r = new LDIFReader(initialDataLdif.getAbsoluteFile());
//      Entry readEntry;
//      while ((readEntry = r.readEntry()) != null) {
//        bindedConnection.add(new AddRequest(readEntry.toLDIF()));
//      }
//    }
//
//    private LDAPConnection bind(LDAPConnection connection) throws Exception {
//      Tuple<String, String> bindDNAndPassword = getSearchingUserConfig();
//      BindResult bindResult = connection.bind(bindDNAndPassword.v1(), bindDNAndPassword.v2());
//      if (!ResultCode.SUCCESS.equals(bindResult.getResultCode())) {
//        throw new ContainerCreationException("Cannot init LDAP due to bind problem");
//      }
//      return connection;
//    }
//  };
}
