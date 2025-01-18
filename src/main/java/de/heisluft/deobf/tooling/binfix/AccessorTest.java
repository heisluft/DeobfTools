package de.heisluft.deobf.tooling.binfix;

import de.heisluft.deobf.tooling.debug.Stringifier;
import org.objectweb.asm.Opcodes;

public class AccessorTest {
  private static final int string_add = 256;

  public static void main(String[] args) {
    for(int i = Opcodes.IADD; i <= string_add; i++) {
      String insn = Stringifier.stringifyInsnOp(i);
      int accessCode = accessCode(i, false);
      if(accessCode > -1) System.out.println((insn.isEmpty() ? i : insn) + "->" + accessCode);
    }
  }

  private static int accessCode(int bytecode, boolean longArg) {
    if (Opcodes.IADD <= bytecode && bytecode <= Opcodes.LXOR) {
      if(Opcodes.ISHL <= bytecode && bytecode <= Opcodes.LUSHR && longArg)
        return (bytecode + 13 - Opcodes.IADD) * 2 + 12;
      return (bytecode - Opcodes.IADD) * 2 + 12;
    }
    else if (bytecode == string_add)
      return (Opcodes.LXOR + 1 - Opcodes.IADD) * 2 + 12;
    else
      return -1;
  }
}
