package tech.beshu.ror.mocks

import com.unboundid.ldap.sdk.LDAPConnectionPool
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.{LdapConnectionConfig, LdapConnectionPoolProvider}

object MockLdapConnectionPoolProvider extends LdapConnectionPoolProvider {
  override def connect(connectionConfig: LdapConnectionConfig): Task[LDAPConnectionPool] =
    throw new IllegalStateException("Cannot use it. It's just a mock")

  override def close(): Task[Unit] = Task.pure(())
}
