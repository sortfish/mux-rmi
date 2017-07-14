package easyrmi;

import java.util.concurrent.atomic.AtomicInteger;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;

/**
 * Base classes for collecting performance statistics
 * @author ReneAndersen
 */
class StatisticsProvider {
  private final MetricRegistry registry;

  StatisticsProvider() {
    this(new MetricRegistry());
  }
  
  StatisticsProvider(final StatisticsProvider statistics) {
    this(statistics.registry);
  }
  
  StatisticsProvider(final MetricRegistry registry) {
    this.registry = registry;
  }
  
  /**
   * @return the Metric registry.
   */
  MetricRegistry getRegistry() {
    return registry;
  }
  
  /**
   * A value-class which collects statistics of the collected values.
   */
  abstract class Value {
    final Histogram histogram;

    Value(final Class<?> clazz, final String name) {
      histogram = registry.histogram(MetricRegistry.name(clazz, name));
    }

    protected abstract int get();

    void update() {
      update(get());
    }

    void update(final int value) {
      histogram.update(value);
    }

    @Override
    public String toString() {
      return Integer.toString(get());
    }
  }

  /**
   * A counter-class which obtains values for incrementing and decrementing a counter.
   */
  final class Counter extends Value {
    final AtomicInteger counter = new AtomicInteger();

    Counter(final Class<?> clazz, final String name) {
      super(clazz, name);
    }

    void inc() {
      update(counter.incrementAndGet());
    }

    void dec() {
      update(counter.decrementAndGet());
    }

    @Override
    protected int get() {
      return counter.get();
    }
  }
}
