package easyrmi;

import java.util.concurrent.atomic.AtomicInteger;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;

/**
 * Utility classes for collection performance statistics
 * @author ReneAndersen
 */
public interface Statistics {
  /**
   * The global metric configuration.
   */
  class Metrics {
    private static MetricRegistry registry = new MetricRegistry();
    
    /**
     * Set a new {@link MetricRegistry}
     * @param newRegistry the new registry.
     */
    static void setRegistry(final MetricRegistry newRegistry) {
      registry = newRegistry;
    }
    
    /**
     * @return the current {@link MetricRegistry}
     */
    static MetricRegistry getRegistry() {
      return registry;
    }
  }
  
  /**
   * Construct a name for a statistics value.
   * @param clazz the Class that the statistics value belong to.
   * @param name the local name of the statistics value.
   * @return the resulting name.
   */
  static String nameOf(final Class<?> clazz, String name) {
    return clazz.getSimpleName() + "." + name;
  }
  
  /**
   * A value-class with collects statistics of the collected values.
   */
  abstract class Value {
    final Histogram histogram;

    Value(final Class<?> clazz, final String name) {
      histogram = Metrics.getRegistry().histogram(nameOf(clazz, name));
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
