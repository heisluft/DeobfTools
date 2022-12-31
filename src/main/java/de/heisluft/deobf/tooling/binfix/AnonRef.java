package de.heisluft.deobf.tooling.binfix;

/**
 * A type of reference to an instance inner class, used to detect whether it was an anonymous class.
 */
enum AnonRef {
  /** The class was not referenced at all */
  NONE,
  /** The class was referenced in a way that does not disqualify it from being an anonymous class */
  LEGAL,
  /** The class was referenced in a way that disqualifies it from being an anonymous class */
  DISQUALIFYING
}
