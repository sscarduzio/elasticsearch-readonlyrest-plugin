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
package tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.security

import better.files.File
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier

private [patches] object CreateServiceAccountServiceBridgeClass extends BytecodeJarModifier {
  override def apply(file: File): Unit = {
    addNewFileToJar(
      jar = file,
      filePathString = "org/elasticsearch/plugins/ServiceAccountServiceBridge.class",
      content = bridgeClassBytes()
    )
  }

  private def bridgeClassBytes(): Array[Byte] = {
    import org.objectweb.asm.*
    val cw = new ClassWriter(0)
    val name = "org/elasticsearch/plugins/ServiceAccountServiceBridge"
    cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null)

    cw
      .visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, "instance", "Ljava/lang/Object;", null, null)
      .visitEnd()

    // <init>
    var mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null)
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(1, 1);
    mv.visitEnd()

    // publish(Object)
    mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "publish", "(Ljava/lang/Object;)V", null, null)
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitFieldInsn(Opcodes.PUTSTATIC, name, "instance", "Ljava/lang/Object;")
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(1, 1);
    mv.visitEnd()

    // get():Object
    mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "get", "()Ljava/lang/Object;", null, null)
    mv.visitFieldInsn(Opcodes.GETSTATIC, name, "instance", "Ljava/lang/Object;")
    mv.visitInsn(Opcodes.ARETURN)
    mv.visitMaxs(1, 0);
    mv.visitEnd()

    // clear()
    mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "clear", "()V", null, null)
    mv.visitInsn(Opcodes.ACONST_NULL)
    mv.visitFieldInsn(Opcodes.PUTSTATIC, name, "instance", "Ljava/lang/Object;")
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(1, 0);
    mv.visitEnd()

    cw.visitEnd()
    cw.toByteArray
  }
}
