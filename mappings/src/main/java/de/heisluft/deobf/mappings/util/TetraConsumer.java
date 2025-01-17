package de.heisluft.deobf.mappings.util;

/**
 * A consumer of four independent arguments.
 *
 * @param <T1> The first arg type
 * @param <T2> The second arg type
 * @param <T3> The third arg type
 * @param <T4> The fourth arg type
 *
 */
@FunctionalInterface
public interface TetraConsumer<T1, T2, T3, T4> {
  /**
   * Applies this function to the given arguments.
   *
   * @param t1 the first argument
   * @param t2 the second argument
   * @param t3 the third argument
   * @param t4 the fourth argument
   */
  void accept(T1 t1, T2 t2, T3 t3, T4 t4);
}
