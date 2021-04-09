package de.heisluft.reveng.util;

import java.util.Objects;

@FunctionalInterface
public interface ThrowingFunction<I,O> {
  O apply(I in) throws Exception;

  default <R> ThrowingFunction<I,R> andThen(ThrowingFunction<? super O, ? extends R> after) {
    Objects.requireNonNull(after);
    return (I i) -> after.apply(apply(i));
  }
}
