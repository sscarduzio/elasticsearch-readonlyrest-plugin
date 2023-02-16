package tech.beshu.ror.tools.core.utils.asm

import org.objectweb.asm._

import java.io.{File, InputStream}
import scala.language.postfixOps

object SecurityActionFilterDeactivator extends BytecodeJarModifier {

  def deactivateXpackSecurityFilter(jar: File): Unit = {
    val modifiedSecurityClass = loadAndProcessFileFromJar(
      jar = jar,
      classFileName = "org/elasticsearch/xpack/security/Security",
      processFileContent = doDeactivateXpackSecurityFilter
    )
    updateFileInJar(
      jar = jar,
      destinationPathSting = "/org/elasticsearch/xpack/security/Security.class",
      newContent = modifiedSecurityClass
    )
  }

  private def doDeactivateXpackSecurityFilter(moduleInputStream: InputStream) = {
    val reader = new ClassReader(moduleInputStream)
    val writer = new ClassWriter(reader, 0)
    reader.accept(new EsClassVisitor(writer), 0)
    writer.toByteArray
  }

  private class EsClassVisitor(writer: ClassWriter)
    extends ClassVisitor(Opcodes.ASM9, writer) {

    override def visitMethod(access: Int,
                             name: String,
                             descriptor: String,
                             signature: String,
                             exceptions: Array[String]): MethodVisitor = {
      name match {
        case "getActionFilters" =>
          new EsMethodVisitor(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class EsMethodVisitor(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      underlying.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/Collections",
        "emptyList",
        "()Ljava/util/List;",
        false
      )
      underlying.visitInsn(Opcodes.ARETURN)
      underlying.visitMaxs(1, 1)
      underlying.visitEnd()
    }

//    override def visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean): Unit = {
//      println(s"visitMethodInsn: $opcode, $owner, $name, $descriptor, $isInterface")
//      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
//    }
  }

  // todo: remove
  def main(args: Array[String]): Unit = {
    val jar = os.root / "tmp" / "x-pack-security-8.6.1.jar"
    val copiedJar = os.root / "tmp" / "x-pack-security-8.6.1-bak.jar"
    os.remove(copiedJar)
    os.copy(jar, copiedJar)
    SecurityActionFilterDeactivator.deactivateXpackSecurityFilter(copiedJar toIO)
  }
}
