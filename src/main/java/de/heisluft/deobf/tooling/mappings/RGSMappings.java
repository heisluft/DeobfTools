package de.heisluft.deobf.tooling.mappings;

import java.util.HashMap;
import java.util.Map;

/**
 * RGS Mappings can relocate classes to other packages based on if they match a certain regex.
 */
final class RGSMappings extends Mappings {
  /**
   * A map mapping regexes to packages. packages end on a '/'.
   */
  final Map<String, String> packages = new HashMap<>();

  @Override
  public String getClassName(String className) {
    for(Map.Entry<String, String> relocation : packages.entrySet()) {
      String cNameOnly = className.contains("/") ? className.substring(className.lastIndexOf('/') + 1) : className;
      if(className.matches(relocation.getKey())) return relocation.getValue() + super.getClassName(cNameOnly);
    }
    return super.getClassName(className);
  }

  @Override
  public boolean hasClassMapping(String className) {
    return super.hasClassMapping(className) || packages.keySet().stream().anyMatch(className::matches);
  }
}
