package de.heisluft.function;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

public class FunctionalUtil {

  public static <I, O> Function<I, O> thr(ThrowingFunction<I, O> f) {
    return i -> {
      try {
        return f.applyT(i);
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    };
  }

  public static <I1, I2, O> BiFunction<I1, I2, O> thrb(ThrowingBiFunction<I1, I2, O> f) {
    return (i1, i2) -> {
      try {
        return f.applyT(i1, i2);
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    };
  }

  public static <I1, I2> BiConsumer<I1, I2> thrbc(ThrowingBiConsumer<I1, I2> f) {
    return (i1, i2) -> {
      try {
        f.acceptT(i1, i2);
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    };
  }

  public static <I> Consumer<I> thrc(ThrowingConsumer<I> f) {
    return i -> {
      try {
        f.acceptT(i);
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    };
  }

  public static <I> Function<I,I> retain(Consumer<I> consumer) {
    return i -> {
      consumer.accept(i);
      return i;
    };
  }

  public static <I, R, O> Function<I, O> chain(Function<I, R> f1, Function<R, O> f2) {
    return i -> f2.apply(f1.apply(i));
  }

  public static <I, R, O> Function<I, O> chain(ThrowingFunction<I, R> f1, ThrowingFunction<R, O> f2) {
    return i -> f2.apply(f1.apply(i));
  }

  public interface ThrowingFunction<I, O> extends Function<I, O> {

    @Override
    default O apply(I i) {
      try {
        return applyT(i);
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    }

    O applyT(I i) throws Exception;
  }

  public interface ThrowingConsumer<I> extends Consumer<I>{

    @Override
    default void accept(I i) {
      try {
        acceptT(i);
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    }
    void acceptT(I i) throws Exception;
  }

  public interface ThrowingBiFunction<I1, I2, O> extends BiFunction<I1, I2, O> {
    @Override
    default O apply(I1 i1, I2 i2) {
      try {
        return applyT(i1, i2);
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    }

    O applyT(I1 i1, I2 i2) throws Exception;
  }

  public interface ThrowingBiConsumer<I1, I2> extends BiConsumer<I1, I2> {

    @Override
    default void accept(I1 i1, I2 i2) {
      try {
        acceptT(i1, i2);
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    }

    void acceptT(I1 i, I2 i2) throws Exception;
  }
}
