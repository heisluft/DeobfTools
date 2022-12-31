package de.heisluft.deobf.tooling.debug;

import org.objectweb.asm.Opcodes;

/**
 * Utility class providing several toString methods for OpCodes and Accesses
 */
public class Stringifier {

  /**
   * turns the access flags of a class into a human readable String
   *
   * @param access
   *     the access to stringify
   *
   * @return the resulting String
   */
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

  /**
   * turns the access flags of a field into a human readable String
   *
   * @param access
   *     the access to stringify
   *
   * @return the resulting String
   */
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

  /**
   * turns the access flags of a method into a human readable String
   *
   * @param access
   *     the access to stringify
   *
   * @return the resulting String
   */
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


  /**
   * turns the given OpCode into its mnemonic
   *
   * @param opCode
   *     the OpCode to stringify
   *
   * @return the resulting String
   */
  public static String stringifyInsnOp(int opCode) {
    switch(opCode) {
      case Opcodes.NOP:
        return "NOP";
      case Opcodes.ACONST_NULL:
        return "ACONST_NULL";
      case Opcodes.ICONST_M1:
        return "ICONST_M1";
      case Opcodes.ICONST_0:
        return "ICONST_0";
      case Opcodes.ICONST_1:
        return "ICONST_1";
      case Opcodes.ICONST_2:
        return "ICONST_2";
      case Opcodes.ICONST_3:
        return "ICONST_3";
      case Opcodes.ICONST_4:
        return "ICONST_4";
      case Opcodes.ICONST_5:
        return "ICONST_5";
      case Opcodes.LCONST_0:
        return "LCONST_0";
      case Opcodes.LCONST_1:
        return "LCONST_1";
      case Opcodes.FCONST_0:
        return "FCONST_0";
      case Opcodes.FCONST_1:
        return "FCONST_1";
      case Opcodes.FCONST_2:
        return "FCONST_2";
      case Opcodes.DCONST_0:
        return "DCONST_0";
      case Opcodes.DCONST_1:
        return "DCONST_1";
      case Opcodes.BIPUSH:
        return "BIPUSH";
      case Opcodes.SIPUSH:
        return "SIPUSH";
      case Opcodes.LDC:
        return "LDC";
      case Opcodes.ILOAD:
        return "ILOAD";
      case Opcodes.LLOAD:
        return "LLOAD";
      case Opcodes.FLOAD:
        return "FLOAD";
      case Opcodes.DLOAD:
        return "DLOAD";
      case Opcodes.ALOAD:
        return "ALOAD";
      case Opcodes.IALOAD:
        return "IALOAD";
      case Opcodes.LALOAD:
        return "LALOAD";
      case Opcodes.FALOAD:
        return "FALOAD";
      case Opcodes.DALOAD:
        return "DALOAD";
      case Opcodes.AALOAD:
        return "AALOAD";
      case Opcodes.BALOAD:
        return "BALOAD";
      case Opcodes.CALOAD:
        return "CALOAD";
      case Opcodes.SALOAD:
        return "SALOAD";
      case Opcodes.ISTORE:
        return "ISTORE";
      case Opcodes.LSTORE:
        return "LSTORE";
      case Opcodes.FSTORE:
        return "FSTORE";
      case Opcodes.DSTORE:
        return "DSTORE";
      case Opcodes.ASTORE:
        return "ASTORE";
      case Opcodes.IASTORE:
        return "IASTORE";
      case Opcodes.LASTORE:
        return "LASTORE";
      case Opcodes.FASTORE:
        return "FASTORE";
      case Opcodes.DASTORE:
        return "DASTORE";
      case Opcodes.AASTORE:
        return "AASTORE";
      case Opcodes.BASTORE:
        return "BASTORE";
      case Opcodes.CASTORE:
        return "CASTORE";
      case Opcodes.SASTORE:
        return "SASTORE";
      case Opcodes.POP:
        return "POP";
      case Opcodes.POP2:
        return "POP2";
      case Opcodes.DUP:
        return "DUP";
      case Opcodes.DUP_X1:
        return "DUP_X1";
      case Opcodes.DUP_X2:
        return "DUP_X2";
      case Opcodes.DUP2:
        return "DUP2";
      case Opcodes.DUP2_X1:
        return "DUP2_X1";
      case Opcodes.DUP2_X2:
        return "DUP2_X2";
      case Opcodes.SWAP:
        return "SWAP";
      case Opcodes.IADD:
        return "IADD";
      case Opcodes.LADD:
        return "LADD";
      case Opcodes.FADD:
        return "FADD";
      case Opcodes.DADD:
        return "DADD";
      case Opcodes.ISUB:
        return "ISUB";
      case Opcodes.LSUB:
        return "LSUB";
      case Opcodes.FSUB:
        return "FSUB";
      case Opcodes.DSUB:
        return "DSUB";
      case Opcodes.IMUL:
        return "IMUL";
      case Opcodes.LMUL:
        return "LMUL";
      case Opcodes.FMUL:
        return "FMUL";
      case Opcodes.DMUL:
        return "DMUL";
      case Opcodes.IDIV:
        return "IDIV";
      case Opcodes.LDIV:
        return "LDIV";
      case Opcodes.FDIV:
        return "FDIV";
      case Opcodes.DDIV:
        return "DDIV";
      case Opcodes.IREM:
        return "IREM";
      case Opcodes.LREM:
        return "LREM";
      case Opcodes.FREM:
        return "FREM";
      case Opcodes.DREM:
        return "DREM";
      case Opcodes.INEG:
        return "INEG";
      case Opcodes.LNEG:
        return "LNEG";
      case Opcodes.FNEG:
        return "FNEG";
      case Opcodes.DNEG:
        return "DNEG";
      case Opcodes.ISHL:
        return "ISHL";
      case Opcodes.LSHL:
        return "LSHL";
      case Opcodes.ISHR:
        return "ISHR";
      case Opcodes.LSHR:
        return "LSHR";
      case Opcodes.IUSHR:
        return "IUSHR";
      case Opcodes.LUSHR:
        return "LUSHR";
      case Opcodes.IAND:
        return "IAND";
      case Opcodes.LAND:
        return "LAND";
      case Opcodes.IOR:
        return "IOR";
      case Opcodes.LOR:
        return "LOR";
      case Opcodes.IXOR:
        return "IXOR";
      case Opcodes.LXOR:
        return "LXOR";
      case Opcodes.IINC:
        return "IINC";
      case Opcodes.I2L:
        return "I2L";
      case Opcodes.I2F:
        return "I2F";
      case Opcodes.I2D:
        return "I2D";
      case Opcodes.L2I:
        return "L2I";
      case Opcodes.L2F:
        return "L2F";
      case Opcodes.L2D:
        return "L2D";
      case Opcodes.F2I:
        return "F2I";
      case Opcodes.F2L:
        return "F2L";
      case Opcodes.F2D:
        return "F2D";
      case Opcodes.D2I:
        return "D2I";
      case Opcodes.D2L:
        return "D2L";
      case Opcodes.D2F:
        return "D2F";
      case Opcodes.I2B:
        return "I2B";
      case Opcodes.I2C:
        return "I2C";
      case Opcodes.I2S:
        return "I2S";
      case Opcodes.LCMP:
        return "LCMP";
      case Opcodes.FCMPL:
        return "FCMPL";
      case Opcodes.FCMPG:
        return "FCMPG";
      case Opcodes.DCMPL:
        return "DCMPL";
      case Opcodes.DCMPG:
        return "DCMPG";
      case Opcodes.IFEQ:
        return "IFEQ";
      case Opcodes.IFNE:
        return "IFNE";
      case Opcodes.IFLT:
        return "IFLT";
      case Opcodes.IFGE:
        return "IFGE";
      case Opcodes.IFGT:
        return "IFGT";
      case Opcodes.IFLE:
        return "IFLE";
      case Opcodes.IF_ICMPEQ:
        return "IF_ICMPEQ";
      case Opcodes.IF_ICMPNE:
        return "IF_ICMPNE";
      case Opcodes.IF_ICMPLT:
        return "IF_ICMPLT";
      case Opcodes.IF_ICMPGE:
        return "IF_ICMPGE";
      case Opcodes.IF_ICMPGT:
        return "IF_ICMPGT";
      case Opcodes.IF_ICMPLE:
        return "IF_ICMPLE";
      case Opcodes.IF_ACMPEQ:
        return "IF_ACMPEQ";
      case Opcodes.IF_ACMPNE:
        return "IF_ACMPNE";
      case Opcodes.GOTO:
        return "GOTO";
      case Opcodes.JSR:
        return "JSR";
      case Opcodes.RET:
        return "RET";
      case Opcodes.TABLESWITCH:
        return "TABLESWITCH";
      case Opcodes.LOOKUPSWITCH:
        return "LOOKUPSWITCH";
      case Opcodes.IRETURN:
        return "IRETURN";
      case Opcodes.LRETURN:
        return "LRETURN";
      case Opcodes.FRETURN:
        return "FRETURN";
      case Opcodes.DRETURN:
        return "DRETURN";
      case Opcodes.ARETURN:
        return "ARETURN";
      case Opcodes.RETURN:
        return "RETURN";
      case Opcodes.GETSTATIC:
        return "GETSTATIC";
      case Opcodes.PUTSTATIC:
        return "PUTSTATIC";
      case Opcodes.GETFIELD:
        return "GETFIELD";
      case Opcodes.PUTFIELD:
        return "PUTFIELD";
      case Opcodes.INVOKEVIRTUAL:
        return "INVOKEVIRTUAL";
      case Opcodes.INVOKESPECIAL:
        return "INVOKESPECIAL";
      case Opcodes.INVOKESTATIC:
        return "INVOKESTATIC";
      case Opcodes.INVOKEINTERFACE:
        return "INVOKEINTERFACE";
      case Opcodes.INVOKEDYNAMIC:
        return "INVOKEDYNAMIC";
      case Opcodes.NEW:
        return "NEW";
      case Opcodes.NEWARRAY:
        return "NEWARRAY";
      case Opcodes.ANEWARRAY:
        return "ANEWARRAY";
      case Opcodes.ARRAYLENGTH:
        return "ARRAYLENGTH";
      case Opcodes.ATHROW:
        return "ATHROW";
      case Opcodes.CHECKCAST:
        return "CHECKCAST";
      case Opcodes.INSTANCEOF:
        return "INSTANCEOF";
      case Opcodes.MONITORENTER:
        return "MONITORENTER";
      case Opcodes.MONITOREXIT:
        return "MONITOREXIT";
      case Opcodes.MULTIANEWARRAY:
        return "MULTIANEWARRAY";
      case Opcodes.IFNULL:
        return "IFNULL";
      case Opcodes.IFNONNULL:
        return "IFNONNULL";
      default:
        return ""; // Labels, for example
    }
  }

  /**
   * Turns the descriptor of a given field into a human readable string
   *
   * @param desc the field descriptor
   * @return the resulting String
   */
  public static String stringifyFieldDesc(String desc) {
    StringBuilder builder = new StringBuilder();
    int arrSize = 0;
    while(desc.charAt(arrSize) == '[') arrSize++;

    switch(desc.charAt(arrSize)) {
      case 'I':
        builder.append("int");
        break;
      case 'B':
        builder.append("byte");
        break;
      case 'Z':
        builder.append("boolean");
        break;
      case 'C':
        builder.append("char");
        break;
      case 'J':
        builder.append("long");
        break;
      case 'S':
        builder.append("short");
        break;
      case 'F':
        builder.append("float");
        break;
      case 'D':
        builder.append("double");
        break;
      case 'L':
        builder.append(desc, arrSize + 1, desc.length() - 1);
    }

    for(int i = 0; i < arrSize; i++) builder.append("[]");
    return builder.append(" ").toString();
  }
}
