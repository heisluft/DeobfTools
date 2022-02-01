package de.heisluft.reveng.mappings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Mappings {


  final Map<String, String> classMappings = new HashMap<>();
  //className -> fieldName -> remappedName
  final Map<String, Map<String, String>> fieldMappings = new HashMap<>();
  //className -> methodName + methodDesc -> remappedName
  final Map<String, Map<String, String>> methodMappings = new HashMap<>();
  //className + methodName + methodDesc -> list of exceptions to add (can be obf.)
  final Map<String, List<String>> exceptions = new HashMap<>();

  public String getClassName(String className) {
    return classMappings.get(className);
  }

  public String getMethodName(String className, String methodName, String methodDescriptor) {
    return methodMappings.getOrDefault(className, new HashMap<>()).get(methodName + methodDescriptor);
  }

  public String getFieldName(String className, String fieldName) {
    return fieldMappings.getOrDefault(className, new HashMap<>()).get(fieldName);
  }

  public List<String> getExceptions(String className, String methodName, String methodDescriptor) {
    return exceptions.get(className + methodName + methodDescriptor);
  }

  public boolean hasClassMapping(String className) {
    return classMappings.containsKey(className);
  }
}
