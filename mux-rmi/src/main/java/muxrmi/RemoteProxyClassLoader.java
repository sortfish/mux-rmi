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

import java.util.Arrays;


/**
 * A {@link ClassLoader} that will invoke a prioritized list of class loaders 
 * when trying to find the class for a class name.
 * @author Rene Andersen
 */
class RemoteProxyClassLoader extends ClassLoader {
  private final ClassLoader[] classLoaders;

  RemoteProxyClassLoader(final ClassLoader... classLoaders) {
    this.classLoaders = classLoaders;
  }

  /** {@inheritDoc} */
  @Override
  protected Class<?> findClass(final String name) throws ClassNotFoundException {
    try {
      for (final ClassLoader classLoader : classLoaders) {
        if (classLoader != null) {
          return classLoader.loadClass(name);
        }
      }
    } catch (final Exception|IllegalAccessError e) {
      // continue to next class loader
    }
    throw new ClassNotFoundException(name + " (in " + Arrays.asList(classLoaders) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
  }
}
