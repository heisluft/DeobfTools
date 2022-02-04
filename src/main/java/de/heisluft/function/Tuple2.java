package de.heisluft.function;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Stream;

import static de.heisluft.function.FunctionalUtil.ThrowingConsumer;
import static de.heisluft.function.FunctionalUtil.ThrowingFunction;
import static de.heisluft.function.FunctionalUtil.ThrowingBiConsumer;

/**
 * A simple implementation of a 2 object tuple, written with functional code in mind.
 * A tuple is an immutable aggregation of values.
 *
 * @param <T1> The type of the first object
 * @param <T2> The type of the second object
 */
public class Tuple2<T1, T2> {
  /** the first object */
  public final T1 _1;
  /** the second object */
  public final T2 _2;

  /**
   * Constructs a new Tuple with the designated values
   * @param __1 the value of the first object
   * @param __2 the value of the second object
   */
  public Tuple2(T1 __1, T2 __2) {
    _1 = __1;
    _2 = __2;
  }

  /**
   * Constructs a new Tuple from an array of length 2.
   * This method is static to allow for T1 to be equal to T2
   *
   * @param array the array
   * @param <T> the Type of the array
   * @return the new Tuple
   */
  public static <T> Tuple2<T, T> from(T[] array) {
    if(array.length != 2) throw new IllegalArgumentException();
    return new Tuple2<>(array[0], array[1]);
  }

  /**
   * Constructs a new Tuple from a Map entry.
   * _1 will hold the key, _2 will hold the value
   * @param mapEntry the entry to construct from
   */
  public Tuple2(Map.Entry<T1, T2> mapEntry) {
    _1 = mapEntry.getKey();
    _2 = mapEntry.getValue();
  }

  /**
   * Denotes a function which creates a Tuple from two different functions invoked on the same objects.
   * Useful for capturing values within Streams. Intended use:
   * <pre>{@code
   * List<Person> myList = ...;
   * myList.stream().map(Tuple2.from(person -> person.name, Person::getCar))
   *     .map(t -> t._1 + " drives a " + t._2.getBrandName()).forEach(System.out::println));
   * }</pre>
   *
   * @param f1 the function used for obtaining the first value
   * @param f2 the function used for obtaining the second value
   * @param <I> the Type the functions are invoked on
   * @param <T1> the return type of f1
   * @param <T2> the return type of f2
   *
   * @return the tuple yielding function
   */
  public static <I, T1, T2> Function<I, Tuple2<T1, T2>> from(Function<I, T1> f1, Function<I, T2> f2) {
    return i -> new Tuple2<>(f1.apply(i), f2.apply(i));
  }

  /**
   * Denotes a function which creates a Tuple from two different functions invoked on the same objects.
   * Useful for capturing values within Streams. Intended use:
   * <pre>{@code
   * List<Person> myList = ...;
   * myList.stream().map(Tuple2.from(person -> person.name, Person::getCar))
   *     .map(t -> t._1 + " drives a " + t._2.getBrandName()).forEach(System.out::println));
   * }</pre>
   *
   * @param f1 the function used for obtaining the first value
   * @param f2 the function used for obtaining the second value
   * @param <I> the Type the functions are invoked on
   * @param <T1> the return type of f1
   * @param <T2> the return type of f2
   *
   * @return the tuple yielding function
   */
  public static <I, T1, T2> Function<I, Tuple2<T1, T2>> from(Function<I, T1> f1, ThrowingFunction<I, T2> f2) {
    return i -> new Tuple2<>(f1.apply(i), f2.apply(i));
  }

  public Tuple2<T2, T1> switchMembers() {
    return new Tuple2<>(_2, _1);
  }

  /**
   * Returns a stream of a Map. Entries are converted to Tuples via the respective constructor
   * @param map the map to stream
   * @param <T1> the key type of the map
   * @param <T2> the value type of the map
   * @return the created Stream
   */
  public static <T1, T2> Stream<Tuple2<T1, T2>> streamMap(Map<T1, T2> map) {
    return map.entrySet().stream().map(Tuple2::new);
  }

  /**
   * Denotes a function which creates a Tuple from two different functions invoked on the same objects.
   * Useful for capturing values within Streams. Intended use:
   * <pre>{@code
   * List<Person> myList = ...;
   * myList.stream().map(Tuple2.from(person -> person.name, Person::getCar))
   *     .map(t -> t._1 + " drives a " + t._2.getBrandName()).forEach(System.out::println));
   * }</pre>
   *
   * @param f1 the function used for obtaining the first value
   * @param f2 the function used for obtaining the second value
   * @param <I> the Type the functions are invoked on
   * @param <T1> the return type of f1
   * @param <T2> the return type of f2
   *
   * @return the tuple yielding function
   */
  public static <I, T1, T2> Function<I, Tuple2<T1, T2>> from(ThrowingFunction<I, T1> f1, ThrowingFunction<I, T2> f2) {
    return i -> new Tuple2<>(f1.apply(i), f2.apply(i));
  }

  /**
   * Gives back a Function expanding a value to a Tuple.
   * The tuples first value will be the output of f1(value)
   * and the second value will be the input value itself.
   *
   * @param f1 the function to construct the first value of the tuple
   * @param <I> the parameter type of the function
   * @param <T1> the return type of the function
   * @return the resulting function
   */
  public static <T1, I> Function<I, Tuple2<T1, I>> expandFirst(ThrowingFunction<I, T1> f1) {
    return i -> new Tuple2<>(f1.apply(i), i);
  }

  /**
   * Gives back a Function expanding a value to a Tuple.
   * The tuples second value will be the output of f2(value)
   * and the first value will be the input value itself.
   *
   * @param f2 the function to construct the second value of the tuple
   * @param <I> the parameter type of the function
   * @param <T2> the return type of the function
   * @return the resulting function
   */
  public static <I, T2> Function<I, Tuple2<I, T2>> expandSecond(ThrowingFunction<I, T2> f2) {
    return i -> new Tuple2<>(i, f2.apply(i));
  }

  public <T3> T3 map(BiFunction<T1,T2,T3> function) {
    return function.apply(_1, _2);
  }

  /**
   * Maps the first value of this tuple to an object of type &lt;T3&gt; and returns the resulting tuple.
   * Note that the second value is not duplicated, so calling {@link #map2(Function)} with
   * <pre>{@code T2::clone}</pre> as an argument might be desired if T2 is not an immutable type.
   *
   * @param function the function used to map
   * @param <T3> the return type of the function
   * @return the resulting tuple.
   */
  public <T3> Tuple2<T3, T2> map1(Function<T1, T3> function) {
    return new Tuple2<>(function.apply(_1), _2);
  }

  /**
   * Maps the first value of this tuple to an object of type &lt;T3&gt; and returns the resulting tuple.
   * Note that the second value is not duplicated, so calling {@link #map2(Function)} with
   * <pre>{@code T2::clone}</pre> as an argument might be desired if T2 is not an immutable type.
   *
   * @param function the function used to map
   * @param <T3> the return type of the function
   * @return the resulting tuple.
   */
  public <T3> Tuple2<T3, T2> map1(ThrowingFunction<T1, T3> function) {
    return new Tuple2<>(function.apply(_1), _2);
  }

  /**
   * Maps the second value of this tuple to an object of type &lt;T3&gt; and returns the resulting tuple.
   * Note that the first value is not duplicated, so calling {@link #map1(Function)} with
   * <pre>{@code T1::clone}</pre> as an argument might be desired if T1 is not an immutable type.
   *
   * @param function the function used to map
   * @param <T3> the return type of the function
   * @return the resulting tuple.
   */
  public <T3> Tuple2<T1, T3> map2(Function<T2, T3> function) {
    return new Tuple2<>(_1, function.apply(_2));
  }

  /**
   * Maps the second value of this tuple to an object of type &lt;T3&gt; and returns the resulting tuple.
   * Note that the first value is not duplicated, so calling {@link #map1(Function)} with
   * <pre>{@code T1::clone}</pre> as an argument might be desired if T1 is not an immutable type.
   *
   * @param function the function used to map
   * @param <T3> the return type of the function
   * @return the resulting tuple.
   */
  public <T3> Tuple2<T1, T3> map2(ThrowingFunction<T2, T3> function) {
    return new Tuple2<>(_1, function.apply(_2));
  }

  /**
   * Consumes the first value, giving back the second one
   * @param consumer a consumer to apply to the first value
   * @return the second value
   */
  public T2 consume1(Consumer<T1> consumer) {
    consumer.accept(_1);
    return _2;
  }

  /**
   * Consumes the first value, giving back the second one
   * @param consumer a consumer to apply to the first value
   * @return the second value
   */
  public T2 consume1(ThrowingConsumer<T1> consumer) {
    consumer.accept(_1);
    return _2;
  }

  /**
   * Consumes the second value, giving back the first one
   * @param consumer a consumer to apply to the second value
   * @return the first value
   */
  public T1 consume2(Consumer<T2> consumer) {
    consumer.accept(_2);
    return _1;
  }

  /**
   * Consumes the second value, giving back the first one
   * @param consumer a consumer to apply to the second value
   * @return the first value
   */
  public T1 consume2(ThrowingConsumer<T2> consumer) {
    consumer.accept(_2);
    return _1;
  }

  /**
   * Consumes this Tuple by invoking Consumer.accept(_1, _2)
   * @param consumer the consumer
   */
  public void consume(BiConsumer<T1, T2> consumer) {
    consumer.accept(_1, _2);
  }

  /**
   * Consumes this Tuple by invoking Consumer.accept(_1, _2)
   * @param consumer the consumer
   */
  public void consume(ThrowingBiConsumer<T1, T2> consumer) {
    consumer.accept(_1, _2);
  }

  /**
   * @return the first value
   */
  public T1 _1() {
    return _1;
  }

  /**
   * @return the second value
   */
  public T2 _2() {
    return _2;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Tuple2<?, ?> tuple2 = (Tuple2<?, ?>) o;
    return Objects.equals(_1, tuple2._1) && Objects.equals(_2, tuple2._2);
  }

  @Override
  public String toString() {
    return "Tuple2(" + _1 + ", " + _2 + ')';
  }

  @Override
  public int hashCode() {
    return Objects.hash(_1, _2);
  }

  /**
   * Allows a Stream of Tuples to be automatically converted to a map. _1 will be the key and _2 the
   * value of each respective Entry
   * @param <T1> the key type
   * @param <T2> the value type
   *
   * @return the collector
   */
  public static <T1,T2> Collector<Tuple2<T1, T2>, ?, Map<T1, T2>> toMapCollector() {
    return Collector.of(HashMap::new, (map, tuple) -> map.put(tuple._1, tuple._2), (map1, map2) -> {map1.putAll(map2); return map1;}, Collector.Characteristics.IDENTITY_FINISH);
  }
}