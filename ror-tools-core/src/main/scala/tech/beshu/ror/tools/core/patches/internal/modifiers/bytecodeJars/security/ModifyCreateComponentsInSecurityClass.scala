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
import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier

import java.io.InputStream

/*
  Patches the Security#createComponents method to intercept the ServiceAccountService and ApiKeyService
  instances after they are created, publishing each to its respective bridge class so that the ROR plugin
  can access these X-Pack services across plugin/classloader boundaries.

  Strategy: rename the original public createComponents to internalCreateComponents,
  then generate a new createComponents wrapper (with the same signature) that calls
  internalCreateComponents, iterates the returned components, and publishes
  ServiceAccountService/ApiKeyService instances.

  The descriptor is captured dynamically from the original method, so this works
  regardless of whether the ES version uses the old multi-parameter signature
  (ES 8.x: Client, ClusterService, ThreadPool, ...) or the new PluginServices
  signature (ES 9.x+).
*/
private[patches] object ModifyCreateComponentsInSecurityClass
  extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/security/Security.class",
      processFileContent = doModify
    )
  }

  private def doModify(moduleInputStream: InputStream) = {
    val reader = new ClassReader(moduleInputStream)
    val writer = new ClassWriter(reader, 0)
    reader.accept(new EsClassVisitor(writer), 0)
    writer.toByteArray
  }

  private class EsClassVisitor(writer: ClassWriter)
    extends ClassVisitor(Opcodes.ASM9, writer) {

    private var capturedDescriptor: String = _
    private var capturedSignature: String = _
    private var capturedExceptions: Array[String] = _

    override def visitMethod(access: Int,
                             name: String,
                             descriptor: String,
                             signature: String,
                             exceptions: Array[String]): MethodVisitor = {
      name match {
        case "createComponents" if (access & Opcodes.ACC_PUBLIC) != 0 =>
          capturedDescriptor = descriptor
          capturedSignature = signature
          capturedExceptions = exceptions
          super.visitMethod(access, "internalCreateComponents", descriptor, signature, exceptions)
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }

    override def visitEnd(): Unit = {
      if (capturedDescriptor != null) {
        generateCreateComponentsWrapper(cv, capturedDescriptor, capturedSignature, capturedExceptions)
      }
      super.visitEnd()
    }
  }

  private def generateCreateComponentsWrapper(cv: ClassVisitor,
                                              descriptor: String,
                                              signature: String,
                                              exceptions: Array[String]): Unit = {
    val argTypes = Type.getArgumentTypes(descriptor)
    // slot 0 = this, then one slot per argument (all are object references)
    val paramSlots = argTypes.length
    // local variable slots: this(1) + params + components(1) + iterator(1) + element(1)
    val componentsSlot = paramSlots + 1
    val iteratorSlot = componentsSlot + 1
    val elementSlot = iteratorSlot + 1
    val maxLocals = elementSlot + 1

    val mv = cv.visitMethod(
      Opcodes.ACC_PUBLIC,
      "createComponents",
      descriptor,
      signature,
      exceptions
    )
    mv.visitCode()

    val tryStart = new Label()
    val tryEnd = new Label()
    val catchHandler = new Label()
    val loopStart = new Label()
    val loopEnd = new Label()
    val afterServiceAccountCheck = new Label()
    val afterApiKeyCheck = new Label()

    mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Exception")

    // Collection components = this.internalCreateComponents(args...);
    mv.visitLabel(tryStart)
    mv.visitVarInsn(Opcodes.ALOAD, 0) // this
    for (i <- 1 to paramSlots) {
      mv.visitVarInsn(Opcodes.ALOAD, i)
    }
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/security/Security",
      "internalCreateComponents",
      descriptor,
      false
    )
    mv.visitVarInsn(Opcodes.ASTORE, componentsSlot)

    // Iterator iter = components.iterator();
    mv.visitVarInsn(Opcodes.ALOAD, componentsSlot)
    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;", true)
    mv.visitVarInsn(Opcodes.ASTORE, iteratorSlot)

    // for (Object c : components) {
    mv.visitLabel(loopStart)
    mv.visitFrame(Opcodes.F_APPEND, 2, Array[Object]("java/util/Collection", "java/util/Iterator"), 0, null)
    mv.visitVarInsn(Opcodes.ALOAD, iteratorSlot)
    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true)
    mv.visitJumpInsn(Opcodes.IFEQ, loopEnd)
    mv.visitVarInsn(Opcodes.ALOAD, iteratorSlot)
    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true)
    mv.visitVarInsn(Opcodes.ASTORE, elementSlot)

    // if (c instanceof ServiceAccountService) { ServiceAccountServiceBridge.publish(c); }
    mv.visitVarInsn(Opcodes.ALOAD, elementSlot)
    mv.visitTypeInsn(Opcodes.INSTANCEOF, "org/elasticsearch/xpack/security/authc/service/ServiceAccountService")
    mv.visitJumpInsn(Opcodes.IFEQ, afterServiceAccountCheck)
    mv.visitVarInsn(Opcodes.ALOAD, elementSlot)
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/plugins/ServiceAccountServiceBridge", "publish", "(Ljava/lang/Object;)V", false)

    // if (c instanceof ApiKeyService) { ApiKeyServiceBridge.publish(c); }
    mv.visitLabel(afterServiceAccountCheck)
    mv.visitFrame(Opcodes.F_APPEND, 1, Array[Object]("java/lang/Object"), 0, null)
    mv.visitVarInsn(Opcodes.ALOAD, elementSlot)
    mv.visitTypeInsn(Opcodes.INSTANCEOF, "org/elasticsearch/xpack/security/authc/ApiKeyService")
    mv.visitJumpInsn(Opcodes.IFEQ, afterApiKeyCheck)
    mv.visitVarInsn(Opcodes.ALOAD, elementSlot)
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/plugins/ApiKeyServiceBridge", "publish", "(Ljava/lang/Object;)V", false)

    mv.visitLabel(afterApiKeyCheck)
    mv.visitFrame(Opcodes.F_CHOP, 1, null, 0, null)
    mv.visitJumpInsn(Opcodes.GOTO, loopStart)

    // return components;
    mv.visitLabel(loopEnd)
    mv.visitFrame(Opcodes.F_CHOP, 1, null, 0, null)
    mv.visitVarInsn(Opcodes.ALOAD, componentsSlot)
    mv.visitLabel(tryEnd)
    mv.visitInsn(Opcodes.ARETURN)

    // catch (Exception e) { throw new IllegalStateException("security initialization failed", e); }
    mv.visitLabel(catchHandler)
    // Build the full frame for the catch handler: this + all parameter types
    val localTypes: Array[Object] = Array("org/elasticsearch/xpack/security/Security") ++
      argTypes.map(_.getInternalName)
    mv.visitFrame(
      Opcodes.F_FULL,
      localTypes.length,
      localTypes,
      1,
      Array[Object]("java/lang/Exception")
    )
    mv.visitVarInsn(Opcodes.ASTORE, componentsSlot)
    mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException")
    mv.visitInsn(Opcodes.DUP)
    mv.visitLdcInsn("security initialization failed")
    mv.visitVarInsn(Opcodes.ALOAD, componentsSlot)
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;Ljava/lang/Throwable;)V", false)
    mv.visitInsn(Opcodes.ATHROW)

    mv.visitMaxs(Math.max(4, paramSlots + 2), maxLocals)
    mv.visitEnd()
  }
}
