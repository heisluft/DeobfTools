package de.heisluft.deobf.tooling.structure;

public sealed interface InheritanceStatus permits InheritanceStatus.None,
    InheritanceStatus.Internal, InheritanceStatus.External {
  static None none() {
    return None.INSTANCE;
  }

  static External external() {
    return External.INSTANCE;
  }

  static Internal inside(String className) {
    return new InternalImpl(className);
  }

  final class None implements InheritanceStatus {
    private None() {}
    private static final None INSTANCE = new None();
  }

  sealed interface Internal extends InheritanceStatus permits InternalImpl {
    public abstract String className();
  }

  final class External implements InheritanceStatus {
    private External() {}
    private static final External INSTANCE = new External();
  }
}
