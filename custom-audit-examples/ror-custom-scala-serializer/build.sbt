name := "ror-custom-scala-serializer"
version := "1.0.0"
scalaVersion := "2.13.3"
scalacOptions += "-target:jvm-1.8"

assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false, includeDependency = false)
assemblyJarName in assembly := s"${name.value}-${version.value}.jar"

libraryDependencies += "tech.beshu.ror" %% "audit" % "1.18.9"
//libraryDependencies += "tech.beshu.ror" %% "audit" % "ROR_VERSION"