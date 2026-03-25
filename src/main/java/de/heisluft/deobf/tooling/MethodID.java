package de.heisluft.deobf.tooling;

public record MethodID(String className, String methodName, String methodDesc) {
  @Override
  public String toString() {
    return className + "." + methodName + methodDesc;
  }
}
