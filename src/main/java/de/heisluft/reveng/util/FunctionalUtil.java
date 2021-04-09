package de.heisluft.reveng.util;

import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A Utility for wrapping exception throwing functional code
 */
public interface FunctionalUtil {

  default <T> Predicate<T> not(Predicate<T> toNegate) {
    return t -> !toNegate.test(t);
  }

  default <T> Supplier<T> propagate(Callable<T> func) {
    return errorCatching(func, e -> {
      if(e instanceof RuntimeException) throw (RuntimeException) e;
      else throw new RuntimeException(e);
    });
  }

  default <T> Supplier<T> errorCatching(Callable<T> func, Consumer<Exception> exceptionConsumer) {
    return () -> {
      try {
        return func.call();
      } catch(Exception e) {
        exceptionConsumer.accept(e);
        return null;
      }
    };
  }

  default <T> Supplier<T> catchErr(Callable<T> callable) {
    return errorCatching(callable, Throwable::printStackTrace);
  }

  default <T> Consumer<T> propagate(ThrowingConsumer<T> func) {
    return errorCatching(func, (e,s) -> {
      if(e instanceof RuntimeException) throw (RuntimeException) e;
      else throw new RuntimeException(e);
    });
  }

  default <T> Consumer<T> errorCatching(ThrowingConsumer<T> func, BiConsumer<Exception, T> exceptionConsumer) {
    return s -> {
      try {
        func.accept(s);
      } catch(Exception e) {
        exceptionConsumer.accept(e, s);
      }
    };
  }

  default <T> Consumer<T> catchErr(ThrowingConsumer<T> consumer) {
    return errorCatching(consumer, (e, s) -> e.printStackTrace());
  }

  default <I, O> Function<I, O> propagate(ThrowingFunction<I, O> func) {
    return errorCatching(func, e -> {
      if(e instanceof RuntimeException) throw (RuntimeException) e;
      else throw new RuntimeException(e);
    });
  }

  default <I, O> Function<I, O> errorCatching(ThrowingFunction<I, O> func, Consumer<Exception> exceptionConsumer) {
    return i -> {
      try {
        return func.apply(i);
      } catch(Exception e) {
        exceptionConsumer.accept(e);
        return null;
      }
    };
  }

  default <I, O> Function<I, O> catchErr(ThrowingFunction<I, O> consumer) {
    return errorCatching(consumer, Throwable::printStackTrace);
  }

}
