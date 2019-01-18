package tech.beshu.ror.acl.factory.decoders.definitions

import tech.beshu.ror.acl.blocks.definitions.{ProxyAuthDefinitions, UsersDefinitions}

final case class Definitions(proxies: ProxyAuthDefinitions,
                             users: UsersDefinitions)
