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

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;

/**
 * Base classes for collecting performance statistics with the Metrics library.
 * @author Rene Andersen
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
   * Return the full name of the class with inner-class separators ('$') replaced by dots ('.')
   * @param clazz the class
   * @return the resulting name of the class
   */
  String dotify(final Class<?> clazz) {
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
    protected abstract int get();

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
    void update(final int value) {
      log(value);
      histogram.update(value);
    }
    
    private void log(final int value) {
      log(value, logAll, logVal);
    }

    private void log(final int value, final Logger... loggers) {
      for (final Logger logger : loggers) {
        if (logger.isTraceEnabled()) logger.trace("{}({})", registryName, value);
      }
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

    @Override
    public String toString() {
      return Integer.toString(get());
    }
  }
}
