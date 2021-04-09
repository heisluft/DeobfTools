package de.heisluft.reveng.util;

import java.util.Objects;
import java.util.function.Consumer;

@FunctionalInterface
public interface ThrowingConsumer<T> {
  void accept(T t) throws Exception;

  default ThrowingConsumer<T> andThen(Consumer<? super T> after) {
    Objects.requireNonNull(after);
    return (T t) -> {
      accept(t);
      after.accept(t);
    };
  }

  default ThrowingConsumer<T> andThen(ThrowingConsumer<? super T> after) {
    Objects.requireNonNull(after);
    return (T t) -> {
      accept(t);
      after.accept(t);
    };
  }
}
