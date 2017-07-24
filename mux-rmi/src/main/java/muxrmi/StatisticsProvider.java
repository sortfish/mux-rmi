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
