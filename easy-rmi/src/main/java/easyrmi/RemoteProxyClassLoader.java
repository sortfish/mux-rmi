package easyrmi;

import java.util.Arrays;


/**
 * A {@link ClassLoader} that will delegate to several other class loaders when searching for a class name.
 * @author ReneAndersen
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
