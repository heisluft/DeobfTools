package de.heisluft.reveng.at;

/**
 * AccessTransformers are tools to modify the access of classes and their members, used for example
 * to widen access or to remove final modifiers from methods and fields, so that they can be
 * modified from subclasses and/or externally.
 */
public interface AccessTransformer {

  /**
   * Provides an access flag for a given method
   *
   * @param className
   *     the name of the class containing the method
   * @param methodName
   *     the name of the transformed method
   * @param methodDesc
   *     the descriptor of the transformed method
   * @param access
   *     the current access of the transformed method
   *
   * @return the new access flag, can be identical to the old one
   */
  int getMethodAccess(String className, String methodName, String methodDesc, int access);

  /**
   * Provides an access flag for a given field
   *
   * @param className
   *     the name of the class containing the field
   * @param fieldName
   *     the name of the transformed field
   * @param access
   *     the current access of the transformed field
   *
   * @return the new access flag, can be identical to the old one
   */
  int getFieldAccess(String className, String fieldName, int access);

  /**
   * Provides an access flag for a given class
   *
   * @param className
   *     the name of the transformed class
   * @param access
   *     the current access of the transformed class
   *
   * @return the new access flag, can be identical to the old one
   */
  int getClassAccess(String className, int access);

  /**
   * Checks if the AccessTransformer is to modify a given methods access
   *
   * @param className
   *     the name of the  method
   * @param methodName
   *     the name of the method
   * @param methodDesc
   *     the descriptor of the method
   *
   * @return true if the method would transformed, false otherwise
   */
  boolean providesMethodAccess(String className, String methodName, String methodDesc);
}
