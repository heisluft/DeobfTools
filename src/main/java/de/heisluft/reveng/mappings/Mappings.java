package de.heisluft.reveng.mappings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WIP API
 *
 * Mappings act as an interface for remappers of all kinds.
 * They store information about renamed methods, class names, and field names as well as
 * lists of exceptions associated with methods.
 */
public class Mappings {
  /**
   * All Class mappings, names are jvm names ('/' as delimiter) mapped as follows:
   * classMame -> remappedClassName
   */
  final Map<String, String> classes = new HashMap<>();
  /**
   * All field mappings mapped as follows:
   * className -> fieldName -> remappedName
   */
  final Map<String, Map<String, String>> fields = new HashMap<>();
  /**
   * All method mappings mapped as follows:
   * className -> (methodName + methodDesc) -> remappedName
   */
  final Map<String, Map<String, String>> methods = new HashMap<>();
  /**
   * All exceptions added with the mappings, mapped as follows:
   * className + methodName + methodDesc -> list of exception class names
   * exception class names may or may not be already remapped
   */
  final Map<String, List<String>> exceptions = new HashMap<>();

  /**
   * Retrieves a mapped name for a given class or null if no such mapping exists.
   * Use in conjunction with {@link Mappings#hasClassMapping(String)}
   *
   * @see Mappings#hasClassMapping(String)
   * @param className the classes name
   * @return the mapped name or {@code null} if such a mapping does not exist
   */
  public String getClassName(String className) {
    return classes.get(className);
  }

  /**
   * Retrieves a mapped name for a given method.
   *
   * @param className the name of the class containing the method
   * @param methodName The methods name
   * @param methodDescriptor The methods descriptor
   * @return the mapped name or {@code null} if not found
   */
  public String getMethodName(String className, String methodName, String methodDescriptor) {
    return methods.getOrDefault(className, new HashMap<>()).get(methodName + methodDescriptor);
  }

  /**
   * Retrieves a mapped name for a given field.
   *
   * @param className the name of the class containing the field
   * @param fieldName The fields name
   * @return the mapped name or {@code null} if not found
   */
  public String getFieldName(String className, String fieldName) {
    return fields.getOrDefault(className, new HashMap<>()).get(fieldName);
  }

  /**
   * Retrieves a list of all exceptions associated with a given Method.
   * No guarantee is made whether these names are obfuscated or not.
   *
   * @param className the name of the class containing the method
   * @param methodName The methods name
   * @param methodDescriptor The methods descriptor
   * @return a list of all exceptions for this method, never {@code null}
   */
  public List<String> getExceptions(String className, String methodName, String methodDescriptor) {
    return exceptions.get(className + methodName + methodDescriptor);
  }

  /**
   * Returns if the mappings contain a mapping for a specific class name
   *
   * @param className the class name to test for
   * @return true if there is a mapping for {@code className}, false otherwise
   */
  public boolean hasClassMapping(String className) {
    return classes.containsKey(className);
  }
}