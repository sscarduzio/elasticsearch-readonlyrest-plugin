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
package tech.beshu.ror.tools.patches

import tech.beshu.ror.tools.utils.ModuleOpener

import scala.language.postfixOps
import scala.util.Try

class Es8xxPatch(esPath: os.Path) extends EsPatch {

  private val transportNetty4JarNameRegex = """^transport-netty4-\d+\.\d+\.\d\.jar$""".r
  private val transportNetty4ModulePath = esPath / "modules" / "transport-netty4"
  private val readonlyrestPluginPath = esPath / "plugins" / "readonlyrest"
  private val libPath = esPath / "lib"
  private val modulesPath = esPath / "modules"

  override def assertIsNotPatched(): Try[Unit] = {
    ModuleOpener.openModule(libPath / "elasticsearch-8.3.0.jar" toIO)
    ModuleOpener.openModule(modulesPath / "x-pack-core" / "x-pack-core-8.3.0.jar" toIO)
    findTransportNetty4JarIn(readonlyrestPluginPath)
      .map {
        case Some(_) => throw new IllegalStateException("ReadonlyREST plugin is already patched")
        case None => ()
      }
  }

  override def assertIsPatched(): Try[Unit] = {
    ModuleOpener.openModule(libPath / "elasticsearch-8.3.0.jar" toIO)
    ModuleOpener.openModule(modulesPath / "x-pack-core" / "x-pack-core-8.3.0.jar" toIO)
    findTransportNetty4JarIn(readonlyrestPluginPath)
      .map {
        case Some(_) => ()
        case None => throw new IllegalStateException("ReadonlyREST plugin is not patched yet")
      }
  }

  override def backup(): Try[Unit] = Try {
    // nothing to do
  }

  override def restore(): Try[Unit] = {
    findTransportNetty4JarIn(readonlyrestPluginPath)
      .map {
        case Some(jar) => os.remove(jar)
        case None => throw new IllegalStateException("ReadonlyREST plugin is not patched yet")
      }
  }

  override def execute(): Try[Unit] = {
    findTransportNetty4JarIn(transportNetty4ModulePath)
      .map {
        case Some(jar) =>
          os.copy(from = jar, to = readonlyrestPluginPath / jar.last)
        case None =>
          new IllegalStateException(s"ReadonlyREST plugin cannot be patched due to not found proper jar in $transportNetty4ModulePath")
      }
  }

//  import javassist._
//
//  import java.io.{DataOutputStream, FileOutputStream}
//  import java.net.URI
//  import java.nio.file._
//  import java.util.jar.JarFile
//
//  def modifyJar(pathToJAR: String, pathToClassInsideJAR: String, methodName: String, `type`: String, codeToSetMethod: String): Unit = { //REQUIRES JAVASSIST
//    import scala.collection.JavaConverters._
//    val jarFile = new JarFile(pathToJAR)
//    val entries = jarFile.entries().asScala.toList
//    val zipEntry = jarFile.getEntry(pathToClassInsideJAR)
//    val fis = jarFile.getInputStream(zipEntry)
//    val pool = ClassPool.getDefault
//    val cc = pool.makeClass(fis)
//    fis.close
//    jarFile.close()
//    //Type should be in this format: "()Ljava/lang/String;" (example for String)
//    val cm = cc.getDeclaredMethod("fromString")
//    cm.setBody(codeToSetMethod)
//    //Replace Windows's Stupid backslashes
//    val classFileName = "Version.class" //.replace("\\", "/").substring(0, pathToClassInsideJAR.lastIndexOf('/'))
//    val out = new DataOutputStream(new FileOutputStream(classFileName))
//    cc.getClassFile.write(out)
//    val launchenv = new java.util.HashMap[String, String]();
//    val launchuri = URI.create("jar:" + new File(pathToJAR).toURI)
//    launchenv.put("create", "true")
//    val zipfs = FileSystems.newFileSystem(launchuri, launchenv)
//    try {
//      val externalClassFile = Paths.get(classFileName)
//      val pathInJarfile = zipfs.getPath(pathToClassInsideJAR)
//      // copy a file into the zip file
//      Files.copy(externalClassFile, pathInJarfile, StandardCopyOption.REPLACE_EXISTING)
//    } finally if (zipfs != null) zipfs.close()
//  }

//  def experimentsASM() = {
//    val pathToJAR: String = "lib/elasticsearch-8.3.0.jar"
//    val pathToClassInsideJAR: String = "module-info.class"
//
//    import scala.collection.JavaConverters._
//    val jarFile = new JarFile(pathToJAR)
//    val entries = jarFile.entries().asScala.toList
//    //val zipEntry = jarFile.getEntry(pathToClassInsideJAR)
//    val zipEntry = entries.find { l =>
//      val name = "module-info" + ".class"
//      val b = l.getRealName.startsWith("module-info") && l.getRealName.endsWith(".class")
////      println(s"checking: ${l.getRealName}; isOK? $b; name: $name + ${moduleInfo.replace(" tech.beshu.ror.tools.", "")}" )
//      b
//    }.getOrElse {
//      jarFile.getEntry("module-info")
//    }
//    val fis = jarFile.getInputStream(zipEntry)
//    val reader = new ClassReader(fis)
//    import java.io.PrintWriter
//
//    val writer = new ClassWriter(reader, 0)
//
//    reader.accept(new EsClassVisitor(writer), 0)
//
//
//    //    writer.newModule("org.elasticsearch.server")
//    //    val mv = writer.visitModule("org.elasticsearch.server", Opcodes.ACC_OPEN | Opcodes.V18, null)
//
//    println("-----------------------------------------")
//
//    val reader2 = new ClassReader(writer.toByteArray)
//    reader2.accept(new TraceClassVisitor(new PrintWriter(System.out)), 0)
//
//    val out = new DataOutputStream(new FileOutputStream("/tmp/module-info.class"))
//    out.write(writer.toByteArray)
//    out.flush()
//    out.close()
//
//    import java.net.URI
//    import java.nio.file.{FileSystems, Files, Paths, StandardCopyOption}
//    val env = new java.util.HashMap[String, String]
//    env.put("create", "true")
//    // locate file system by using the syntax
//    // defined in java.net.JarURLConnection
//    val uri = URI.create("jar:" + new File(pathToJAR).toURI)
//
//    val zipfs = FileSystems.newFileSystem(uri, env)
//    try {
//      val externalTxtFile = Paths.get("/tmp/module-info.class")
//      val pathInZipfile = zipfs.getPath("/module-info.class")
//      // copy a file into the zip file
//      Files.copy(externalTxtFile, pathInZipfile, StandardCopyOption.REPLACE_EXISTING)
//    } finally if (zipfs != null) zipfs.close()
//
//    //    writer.toByteArray
//    //    val reader = new ClassReader(fis)
//    //    reader.accept(new EsClassVisitor(), ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG)
//  }
//
//  class EsClassVisitor(writer: ClassWriter) extends ClassVisitor(Opcodes.ASM9, writer) {
//    //    val mv = writer.visitModule("org.elasticsearch.server", Opcodes.ACC_OPEN | Opcodes.V18, null)
//    //    mv.visitEnd()
//    //    override def visitSource(source: String, debug: String): Unit = {
//    //      writer.visitSource(source, debug)
//    //      super.visitSource(source, debug)
//    //    }
//
//    override def visitModule(name: String, access: Int, version: String): ModuleVisitor = {
//      new EsModuleVisitor(super.visitModule(name, access | Opcodes.ACC_OPEN, version))
//    }
//  }
//
//  class EsModuleVisitor(underlying: ModuleVisitor) extends ModuleVisitor(Opcodes.ASM9, underlying) {
//
//    //    override def visitMainClass(mainClass: String): Unit = {
//    //      println(s"visitMainClass: $mainClass")
//    //      super.visitMainClass(mainClass)
//    //    }
//    //
//    //    override def visitPackage(packaze: String): Unit = {
//    //      println(s"visitPackage: $packaze")
//    //      underlying.visitPackage(packaze)
//    //      super.visitPackage(packaze)
//    //    }
//    //
//    //    override def visitRequire(module: String, access: Int, version: String): Unit = {
//    //      println(s"visitRequire: $module, $access, $version")
//    //      underlying.visitRequire(module, access, version)
//    //      super.visitRequire(module, access, version)
//    //    }
//    //
//    //    override def visitExport(packaze: String, access: Int, modules: String*): Unit = {
//    //      println(s"visitExport: $packaze, $access, ${Option(modules).getOrElse(Seq.empty).mkString("&")}")
//    //      underlying.visitExport(packaze, access, Option(modules).getOrElse(Seq.empty): _*)
//    //      super.visitExport(packaze, access, Option(modules).getOrElse(Seq.empty): _*)
//    //    }
//    //
//    override def visitOpen(packaze: String, access: Int, modules: String*): Unit = {
////      println(s"visitOpen: $packaze, $access, ${Option(modules).getOrElse(Seq.empty).mkString("&")}")
////      super.visitOpen(packaze, access, modules: _*)
//    }
//    //
//    //    override def visitUse(service: String): Unit = {
//    //      println(s"visitUse: $service")
//    //      underlying.visitUse(service)
//    //      super.visitUse(service)
//    //    }
//    //
//    //    override def visitProvide(service: String, providers: String*): Unit = {
//    //      println(s"visitProvide: $service, ${Option(providers).getOrElse(Seq.empty)}")
//    //      underlying.visitProvide(service, providers: _*)
//    //      super.visitProvide(service, providers: _*)
//    //    }
//    //
//    //    override def visitEnd(): Unit = {
//    //      println(s"visitEnd")
//    ////      underlying.visitOpen("tech.beshu.ror", Opcodes.ACC_PUBLIC)
//    ////      underlying.visitOpen("tech.beshu.ror.es", Opcodes.ACC_PUBLIC)
//    //      underlying.visitEnd()
//    //      super.visitEnd()
//    //    }
//  }

  private def findTransportNetty4JarIn(path: os.Path) = Try {
    os
      .list(path)
      .filter { file => file.last.matches(transportNetty4JarNameRegex.pattern.pattern()) }
      .toList match {
      case Nil =>
        None
      case foundFile :: Nil =>
        Some(foundFile)
      case many =>
        throw new IllegalStateException(s"More than one file matching regex ${transportNetty4JarNameRegex.pattern.pattern()} in $path; Found: ${many.mkString(", ")}")
    }
  }
}

