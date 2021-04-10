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

  default <T> Supplier<T> propagate(Callable<T> callable) {
    return errorCatching(callable, e -> {
      if(e instanceof RuntimeException) throw (RuntimeException) e;
      else throw new RuntimeException(e);
    });
  }

  default <T> Supplier<T> errorCatching(Callable<T> callable, Consumer<Exception> exceptionConsumer) {
    return () -> {
      try {
        return callable.call();
      } catch(Exception e) {
        exceptionConsumer.accept(e);
        return null;
      }
    };
  }

  default <T> Supplier<T> catchErr(Callable<T> callable) {
    return errorCatching(callable, Throwable::printStackTrace);
  }

  default <T> Consumer<T> propagate(ThrowingConsumer<T> consumer) {
    return errorCatching(consumer, (e,s) -> {
      if(e instanceof RuntimeException) throw (RuntimeException) e;
      else throw new RuntimeException(e);
    });
  }

  default <T> Consumer<T> errorCatching(ThrowingConsumer<T> consumer, BiConsumer<Exception, T> errorHandler) {
    return s -> {
      try {
        consumer.accept(s);
      } catch(Exception e) {
        errorHandler.accept(e, s);
      }
    };
  }

  default <T> Consumer<T> catchErr(ThrowingConsumer<T> consumer) {
    return errorCatching(consumer, (e, s) -> e.printStackTrace());
  }

  default <I, O> Function<I, O> propagate(ThrowingFunction<I, O> function) {
    return errorCatching(function, (e,i) -> {
      if(e instanceof RuntimeException) throw (RuntimeException) e;
      else throw new RuntimeException(e);
    });
  }

  default <I, O> Function<I, O> errorCatching(ThrowingFunction<I, O> function, BiConsumer<Exception, I> errorHandler) {
    return i -> {
      try {
        return function.apply(i);
      } catch(Exception e) {
        errorHandler.accept(e,i);
        return null;
      }
    };
  }

  default <I, O> Function<I, O> catchErr(ThrowingFunction<I, O> function) {
    return errorCatching(function, (e,i) -> e.printStackTrace());
  }

}
