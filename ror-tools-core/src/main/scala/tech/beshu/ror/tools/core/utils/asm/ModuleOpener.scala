package tech.beshu.ror.tools.core.utils.asm

import org.objectweb.asm._

import java.io.{File, InputStream}

object ModuleOpener extends BytecodeJarModifier {

  def openModule(jar: File): Unit = {
    val updatedContentOfModuleInfo = loadAndProcessFileFromJar(
      jar = jar,
      classFileName = "module-info",
      processFileContent = doOpenModule
    )
    updateFileInJar(
      jar = jar,
      destinationPathSting = "/module-info.class",
      newContent = updatedContentOfModuleInfo
    )
  }

  private def doOpenModule(moduleInputStream: InputStream) = {
    val reader = new ClassReader(moduleInputStream)
    val writer = new ClassWriter(reader, 0)
    reader.accept(new EsClassVisitor(writer), 0)
    writer.toByteArray
  }

  private class EsClassVisitor(writer: ClassWriter)
    extends ClassVisitor(Opcodes.ASM9, writer) {

    override def visitModule(name: String, access: Int, version: String): ModuleVisitor = {
      new EsModuleVisitor(super.visitModule(name, access | Opcodes.ACC_OPEN, version))
    }
  }

  private class EsModuleVisitor(underlying: ModuleVisitor)
    extends ModuleVisitor(Opcodes.ASM9, underlying) {

    override def visitOpen(packaze: String, access: Int, modules: String*): Unit = {
      // skipping it to remove opening specific packages
    }
  }
}
