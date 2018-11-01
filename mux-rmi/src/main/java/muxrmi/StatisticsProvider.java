/*
 * MIT License
 *
 * Copyright (c) 2017 Rene Andersen
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package muxrmi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;

/**
 * Base classes for collecting performance statistics with the Metrics library.
 * @author Rene Andersen
 */
public class StatisticsProvider {
  private final MetricRegistry registry;

  /**
   * Collect statistics into a new Metrics registry. 
   */
  public StatisticsProvider() {
    this(new MetricRegistry());
  }
  
  /**
   * Collect statistics into the specified Metrics registry.
   * @param registry the registry.
   */
  public StatisticsProvider(final MetricRegistry registry) {
    this.registry = registry;
  }
  
  /**
   * Collect statistics into the same Metrics registry as used by another statistics provider. 
   * @param statistics the other statistics provider.
   */
  StatisticsProvider(final StatisticsProvider statistics) {
    this(statistics.registry);
  }

  /**
   * @return the Metric registry.
   */
  MetricRegistry getRegistry() {
    return registry;
  }
  
  /**
   * Return the full name of the class with inner-class separators ('$') replaced by dots ('.')
   * @param clazz the class
   * @return the resulting name of the class
   */
  static String dotify(final Class<?> clazz) {
    return clazz.getName().replace("$", ".");
  }
  
  /**
   * A value-class which collects statistics of the collected values.
   */
  abstract class Value {
    final Logger logAll = LoggerFactory.getLogger(Value.class);
    
    final String registryName;
    final Histogram histogram;
    final Logger logVal;    
    
    Value(final Class<?> clazz, final String name) {
      this.registryName = MetricRegistry.name(dotify(clazz), name);
      this.histogram = registry.histogram(registryName);
      this.logVal = LoggerFactory.getLogger(registryName);
    }

    /**
     * Descending classes must implement this method to provide the values
     * that a fed into this histogram when {@link #update()} is called.
     * @return the next value in the data set.
     */
    protected abstract long get();

    /**
     * Update the histogram with the next value returned by {@link #get()}
     */
    void update() {
      update(get());
    }

    /**
     * Update the histogram with the specified value.
     * @param value the value
     */
    void update(final long value) {
      log(value);
      histogram.update(value);
    }
    
    private void log(final long value) {
      log(value, logAll, logVal);
    }

    private void log(final long value, final Logger... loggers) {
      for (final Logger logger : loggers) {
        if (logger.isTraceEnabled()) logger.trace("{}({})", registryName, value);
      }
    }
  }
  
  /**
   * A counted gauge-class which receives count values from an external source, and increment
   * or decrement its count to match the external value. The count values are also pushed to
   * the underlying value-class which collects statistics of the collected values. 
   * <p/> 
   * This class expose two sets of statistics:
   * <ul>
   * <li>The statistics of the underlying value-class (with the specified name)</li>
   * <li>A "snapshot" of the counter value (as the specified name with ".snapshot" appended)</li>
   * </ul>  
   */
  abstract class CountedGauge extends Value {
    final String snapshotRegistryName; 
    final Counter counter;
    
    CountedGauge(final Class<?> clazz, final String name) {
      super(clazz, name);
      this.snapshotRegistryName = MetricRegistry.name(dotify(clazz), name, "snapshot");
      this.counter = registry.counter(snapshotRegistryName);
    }
    
    protected abstract long get();

    synchronized void update(final long value) {
      final long count = counter.getCount();
      if (value >= count) {
        counter.inc(value - count);
      } else {
        counter.dec(count - value);
      }
      super.update(value);
    }
    
    @Override
    public String toString() {
      return Long.toString(get());
    }
  }

  /**
   * A counted value-class which increments and decrements a counter and update an underlying
   * value-class with the current value of the count.
   */
  final class CountedValue extends CountedGauge {
    CountedValue(final Class<?> clazz, final String name) {
      super(clazz, name);
    }

    synchronized void inc() {
      update(get() + 1);
    }

    synchronized void dec() {
      update(get() - 1);
    }

    @Override
    protected long get() {
      return counter.getCount();
    }
  }
}
