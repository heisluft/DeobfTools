package de.heisluft.function;

import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * This Class provides multiple functional interfaces that can throw exceptions. These are useful
 * because they allow for declaring Method references on Methods with checked exceptions.
 * <table>
 *   <thead>
 *     <tr><td>name</td><td>inputs</td><td>outputs</td><td>throws exception</td>
 *     </tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@link Runnable}</td><td>0</td><td>0</td><td>no</td></tr>
 *     <tr><td>{@link Supplier}</td><td>0</td><td>1</td><td>no</td></tr>
 *     <tr><td>{@link Callable}</td><td>0</td><td>1</td><td>yes</td></tr>
 *     <tr><td>{@link Consumer}</td><td>1</td><td>0</td><td>no</td></tr>
 *     <tr><td>{@link ThrowingConsumer}</td><td>1</td><td>0</td><td>yes</td></tr>
 *     <tr><td>{@link Function}</td><td>1</td><td>1</td><td>no</td></tr>
 *     <tr><td>{@link ThrowingFunction}</td><td>1</td><td>1</td><td>yes</td></tr>
 *     <tr><td>{@link BiFunction}</td><td>2</td><td>1</td><td>no</td></tr>
 *     <tr><td>{@link ThrowingBiFunction}</td><td>2</td><td>1</td><td>yes</td></tr>
 *     <tr><td>{@link BiConsumer}</td><td>2</td><td>0</td><td>no</td></tr>
 *     <tr><td>{@link ThrowingBiConsumer}</td><td>2</td><td>0</td><td>yes</td></tr>
 *   </tbody>
 * </table>
 * <p>
 * Additionally, several helper methods are provided to easily wrap those throwing interfaces
 * into the standard ones, rethrowing any exception as a Runtime Exception
 */
public class FunctionalUtil {
  /**
   * Wraps a Supplier around a Callable, rethrowing any exceptions as a RuntimeException
   *
   * @param f
   *     the Callable to wrap
   * @param <O>
   *     the output type
   *
   * @return the wrapped Callable
   */
  public static <O> Supplier<O> thrs(Callable<O> f) {
    return () -> {
      try {
        return f.call();
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    };
  }

  /**
   * Wraps a Function around a ThrowingFunction, rethrowing any exceptions as a RuntimeException
   *
   * @param f
   *     the ThrowingFunction to wrap
   * @param <I>
   *     the input type
   * @param <O>
   *     the output type
   *
   * @return the wrapped ThrowingFunction
   */
  public static <I, O> Function<I, O> thr(ThrowingFunction<I, O> f) {
    return i -> {
      try {
        return f.applyT(i);
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    };
  }

  /**
   * Wraps a BiFunction around a ThrowingBiFunction, rethrowing any exceptions as a
   * RuntimeException
   *
   * @param f
   *     the ThrowingBiFunction to wrap
   * @param <I1>
   *     the first input type
   * @param <I2>
   *     the second input type
   * @param <O>
   *     the output type
   *
   * @return the wrapped ThrowingBiFunction
   */
  public static <I1, I2, O> BiFunction<I1, I2, O> thrb(ThrowingBiFunction<I1, I2, O> f) {
    return (i1, i2) -> {
      try {
        return f.applyT(i1, i2);
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    };
  }

  /**
   * Wraps a BiConsumer around a ThrowingBiConsumer, rethrowing any exceptions as a
   * RuntimeException
   *
   * @param f
   *     the ThrowingBiConsumer to wrap
   * @param <I1>
   *     the first input type
   * @param <I2>
   *     the second input type
   *
   * @return the wrapped ThrowingBiConsumer
   */
  public static <I1, I2> BiConsumer<I1, I2> thrbc(ThrowingBiConsumer<I1, I2> f) {
    return (i1, i2) -> {
      try {
        f.acceptT(i1, i2);
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    };
  }

  /**
   * Wraps a Consumer around a ThrowingConsumer, rethrowing any exceptions as a RuntimeException
   *
   * @param f
   *     the ThrowingConsumer to wrap
   * @param <I>
   *     the input type
   *
   * @return the wrapped ThrowingConsumer
   */
  public static <I> Consumer<I> thrc(ThrowingConsumer<I> f) {
    return i -> {
      try {
        f.acceptT(i);
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    };
  }

  public static <I> Function<I, I> retain(Consumer<I> consumer) {
    return i -> {
      consumer.accept(i);
      return i;
    };
  }

  /**
   * A function that can throw checked exceptions.
   *
   * @param <I>
   *     the input type
   * @param <O>
   *     the output type
   */
  @FunctionalInterface
  public interface ThrowingFunction<I, O> extends Function<I, O> {

    @Override
    default O apply(I i) {
      try {
        return applyT(i);
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    }

    /**
     * like apply but can throw checked exceptions
     *
     * @param i
     *     the input
     *
     * @return the function result
     *
     * @throws Exception
     *     behavior is unspecified. May or may not be thrown
     */
    O applyT(I i) throws Exception;
  }

  /**
   * A consumer that can throw checked exceptions.
   *
   * @param <I>
   *     the input type
   */
  @FunctionalInterface
  public interface ThrowingConsumer<I> extends Consumer<I> {

    @Override
    default void accept(I i) {
      try {
        acceptT(i);
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    }

    /**
     * like accept but can throw checked exceptions
     *
     * @param i
     *     the input
     *
     * @throws Exception
     *     behavior is unspecified. May or may not be thrown
     */
    void acceptT(I i) throws Exception;
  }

  /**
   * A biFunction that can throw checked exceptions.
   *
   * @param <I1>
   *     the first input type
   * @param <I2>
   *     the second input type
   * @param <O>
   *     the output type
   */
  @FunctionalInterface
  public interface ThrowingBiFunction<I1, I2, O> extends BiFunction<I1, I2, O> {
    @Override
    default O apply(I1 i1, I2 i2) {
      try {
        return applyT(i1, i2);
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    }

    /**
     * like apply but can throw checked exceptions
     *
     * @param i1
     *     the first input
     * @param i2
     *     the second input
     *
     * @return the function result
     *
     * @throws Exception
     *     behavior is unspecified. May or may not be thrown
     */
    O applyT(I1 i1, I2 i2) throws Exception;
  }

  /**
   * A biConsumer that can throw checked exceptions.
   *
   * @param <I1>
   *     the first input type
   * @param <I2>
   *     the second input type
   */
  @FunctionalInterface
  public interface ThrowingBiConsumer<I1, I2> extends BiConsumer<I1, I2> {

    @Override
    default void accept(I1 i1, I2 i2) {
      try {
        acceptT(i1, i2);
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    }

    /**
     * like accept but can throw checked exceptions
     *
     * @param i1
     *     the first input
     * @param i2
     *     the second input
     *
     * @throws Exception
     *     behavior is unspecified. May or may not be thrown
     */
    void acceptT(I1 i1, I2 i2) throws Exception;
  }
}
