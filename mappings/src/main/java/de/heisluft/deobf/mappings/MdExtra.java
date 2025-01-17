package de.heisluft.deobf.mappings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An internal immutable data class for storing method extra data.
 */
final class MdExtra {

  /** The empty metadata, safe to use as a fallback instance. */
  static final MdExtra EMPTY = new MdExtra(Collections.emptySet(), Collections.emptyList());

  /** The set of all exceptions for a method, per se mutable, but only to the builder. */
  final Set<String> exceptions;
  /** The list of all parameter names for a method, per se mutable, but only to the builder. */
  final List<String> parameters;

  /** Constructs a new instance, to use only from builders. Outsiders should use the builder. */
  MdExtra() {
    exceptions = new HashSet<>();
    parameters = new ArrayList<>();
  }

  /**
   * Initialized an instance with the given exceptions and parameter mappings.
   *
   * @param exceptions the exceptions to add. Entries are copied over.
   * @param parameters the parameter mappings to add. Entries are copied over.
   */
  MdExtra(Collection<String> exceptions, List<String> parameters) {
    Objects.requireNonNull(exceptions, "exceptions must not be null");
    Objects.requireNonNull(parameters, "parameters must not be null");
    this.exceptions = new HashSet<>(exceptions);
    this.parameters = new ArrayList<>(parameters);
  }

  /**
   * Directly clones existing data.
   *
   * @param toClone the data to clone
   */
  MdExtra(MdExtra toClone) {
    this(toClone.exceptions, toClone.parameters);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MdExtra mdExtra = (MdExtra) o;
    return exceptions.equals(mdExtra.exceptions) && parameters.equals(mdExtra.parameters);
  }

  @Override
  public int hashCode() {
    return Objects.hash(exceptions, parameters);
  }

  @Override
  public String toString() {
    return "MdExtra(exceptions: " + exceptions + ", parameters: " + parameters + ')';
  }
}
