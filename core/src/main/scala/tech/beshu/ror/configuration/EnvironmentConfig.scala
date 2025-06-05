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
package tech.beshu.ror.configuration

import tech.beshu.ror.accesscontrol.blocks.variables.transformation.SupportedVariablesFunctions
import tech.beshu.ror.accesscontrol.matchers.{RandomBasedUniqueIdentifierGenerator, UniqueIdentifierGenerator}
import tech.beshu.ror.providers.*
import tech.beshu.ror.utils.js.{JsCompiler, MozillaJsCompiler}
import tech.beshu.ror.utils.yaml.RorYamlParser

import java.time.Clock

final class EnvironmentConfig(val clock: Clock = Clock.systemUTC(),
                              val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider,
                              val propertiesProvider: PropertiesProvider = JvmPropertiesProvider,
                              val uniqueIdentifierGenerator: UniqueIdentifierGenerator = RandomBasedUniqueIdentifierGenerator,
                              val uuidProvider: UuidProvider = JavaUuidProvider,
                              val jsCompiler: JsCompiler = MozillaJsCompiler,
                              val variablesFunctions: SupportedVariablesFunctions = SupportedVariablesFunctions.default) {

  val yamlParser: RorYamlParser = new RorYamlParser(RorProperties.rorSettingsMaxSize(propertiesProvider))
}

object EnvironmentConfig {

  val default: EnvironmentConfig = new EnvironmentConfig()
}
