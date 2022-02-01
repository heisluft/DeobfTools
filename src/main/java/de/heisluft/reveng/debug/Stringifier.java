package de.heisluft.reveng.debug;

import org.objectweb.asm.Opcodes;

public class Stringifier {

  public static String stringifyClassAccess(int access) {
    StringBuilder builder = new StringBuilder();
    if((access & Opcodes.ACC_PUBLIC) == Opcodes.ACC_PUBLIC) builder.append("public ");
    if((access & Opcodes.ACC_PRIVATE) == Opcodes.ACC_PRIVATE) builder.append("private ");
    if((access & Opcodes.ACC_PROTECTED) == Opcodes.ACC_PROTECTED) builder.append("protected ");
    if((access & Opcodes.ACC_FINAL) == Opcodes.ACC_FINAL) builder.append("final ");
    if((access & Opcodes.ACC_ABSTRACT) == Opcodes.ACC_ABSTRACT) builder.append("abstract ");
    if((access & Opcodes.ACC_SYNTHETIC) == Opcodes.ACC_SYNTHETIC) builder.append("/*synthetic*/ ");
    if((access & Opcodes.ACC_ENUM) == Opcodes.ACC_ENUM) builder.append("enum ");
    else if((access & Opcodes.ACC_INTERFACE) == Opcodes.ACC_INTERFACE) builder.append("interface ");
    else if((access & Opcodes.ACC_ANNOTATION) == Opcodes.ACC_ANNOTATION) builder.append("@interface ");
    else builder.append("class ");
    return builder.toString();
  }

  public static String stringifyFieldAccess(int access) {
    StringBuilder builder = new StringBuilder();
    if((access & Opcodes.ACC_PUBLIC) == Opcodes.ACC_PUBLIC) builder.append("public ");
    if((access & Opcodes.ACC_PRIVATE) == Opcodes.ACC_PRIVATE) builder.append("private ");
    if((access & Opcodes.ACC_PROTECTED) == Opcodes.ACC_PROTECTED) builder.append("protected ");
    if((access & Opcodes.ACC_STATIC) == Opcodes.ACC_STATIC) builder.append("static ");
    if((access & Opcodes.ACC_FINAL) == Opcodes.ACC_FINAL) builder.append("final ");
    if((access & Opcodes.ACC_VOLATILE) == Opcodes.ACC_VOLATILE) builder.append("volatile ");
    if((access & Opcodes.ACC_TRANSIENT) == Opcodes.ACC_TRANSIENT) builder.append("transient ");
    if((access & Opcodes.ACC_SYNTHETIC) == Opcodes.ACC_SYNTHETIC) builder.append("/*synthetic*/ ");
    if((access & Opcodes.ACC_ENUM) == Opcodes.ACC_ENUM) builder.append("/*enum*/ ");
    return builder.toString();
  }

  public static String stringifyMethodAccess(int access) {
    StringBuilder builder = new StringBuilder();
    if((access & Opcodes.ACC_PUBLIC) == Opcodes.ACC_PUBLIC) builder.append("public ");
    if((access & Opcodes.ACC_PRIVATE) == Opcodes.ACC_PRIVATE) builder.append("private ");
    if((access & Opcodes.ACC_PROTECTED) == Opcodes.ACC_PROTECTED) builder.append("protected ");
    if((access & Opcodes.ACC_STATIC) == Opcodes.ACC_STATIC) builder.append("static ");
    if((access & Opcodes.ACC_FINAL) == Opcodes.ACC_FINAL) builder.append("final ");
    if((access & Opcodes.ACC_SYNCHRONIZED) == Opcodes.ACC_SYNCHRONIZED) builder.append("synchronized ");
    if((access & Opcodes.ACC_BRIDGE) == Opcodes.ACC_BRIDGE) builder.append("/*bridge*/ ");
    if((access & Opcodes.ACC_NATIVE) == Opcodes.ACC_NATIVE) builder.append("native ");
    if((access & Opcodes.ACC_ABSTRACT) == Opcodes.ACC_ABSTRACT) builder.append("abstract ");
    if((access & Opcodes.ACC_STRICT) == Opcodes.ACC_STRICT) builder.append("strictfp ");
    if((access & Opcodes.ACC_SYNTHETIC) == Opcodes.ACC_SYNTHETIC) builder.append("/*synthetic*/ ");
    return builder.toString();
  }

  public static String stringifyFieldDesc(String desc) {
    StringBuilder builder = new StringBuilder();
    int arrSize = 0;
    while(desc.charAt(arrSize) == '[') arrSize++;

    switch(desc.charAt(arrSize)) {
      case 'I': builder.append("int"); break;
      case 'B': builder.append("byte"); break;
      case 'Z': builder.append("boolean"); break;
      case 'C': builder.append("char"); break;
      case 'J': builder.append("long"); break;
      case 'S': builder.append("short"); break;
      case 'F': builder.append("float"); break;
      case 'D': builder.append("double"); break;
      case 'L': builder.append(desc, arrSize + 1, desc.length() - 1);
    }

    for(int i = 0; i < arrSize; i++) builder.append("[]");
    return builder.append(" ").toString();
  }
}
