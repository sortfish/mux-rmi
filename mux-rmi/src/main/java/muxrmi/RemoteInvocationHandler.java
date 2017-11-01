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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.rmi.RemoteException;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for a remote reflective {@link InvocationHandler invocation handler}.
 * <p/>
 * This class will handle invocation of methods not in the declared remote interface (such as
 * methods inherited from {@link Object}) and forward the remote method invocations to the 
 * abstract {@code invokeRemote(Method, Object[])} method which descendants must implement.
 * @author Rene Andersen
 */
abstract class RemoteInvocationHandler implements InvocationHandler {
  private static final Logger logger = LoggerFactory.getLogger(RemoteInvocationHandler.class);

  private final Class<?> classType;
  private final List<Method> apiMethods;

  /**
   * Create a proxy invocation handler for a remote interface.
   * @param classType the class-type for the remote interface
   */
  protected RemoteInvocationHandler(final Class<?> classType) {
    this.classType = classType;
    this.apiMethods = Arrays.asList(classType.getMethods());
  }

  /**
   * @return the class type of the remote interface.
   */
  protected Class<?> getClassType() {
    return classType;
  }

  /** {@inheritDoc} */
  @Override
  public Object invoke(final Object proxy,
                       final Method method,
                       final Object[] args) throws Throwable {
    try {
      if (apiMethods.contains(method)) {
        return invokeRemote(method, args);
      }
      if (method.getDeclaringClass().isAssignableFrom(getClass())) {
        return method.invoke(this, args);
      }
      throw new UnsupportedOperationException("Unsupported operation on remote class of type '" + classType + ": " + method);
    } catch (final InvocationTargetException e) {
      throw e.getTargetException();
    } catch (final Exception e) {
      if (logger.isDebugEnabled()) logger.debug("Error in remote method invocation: " + toString(method, args), e);
      throw getMethodCompatibleException(method, e);
    }
  }

  @Override
  public String toString() {
    return "RemoteInvocationHandler [classType=" + classType.getName() + "]";
  }

  /**
   * Called if the method being invoked is in the remote interface.
   * @param method the method being invoked.
   * @param args the arguments to the method.
   * @return the return value from the remote method invocation.
   * @throws InvocationTargetException if the method throws an exception.
   * @throws Exception if any other exception occurs.
   */
  protected abstract Object invokeRemote(final Method method,
                                         final Object[] args) throws InvocationTargetException, Exception;

  /**
   * This method will check if an exception is compatible with the exception types declared by
   * the specified method.
   * <ul>
   * <li>If a compatible exception type is found the exception will be returned as-is.</li>
   * <li>Otherwise, if the method throws {@link RemoteException} the exception will be wrapped
   *     in an instance of this class.</li>
   * <li>Otherwise, the exception will be wrapped in an instance of {@link RuntimeException}.</li>
   * </ul>
   * @param method the method declaring the compatible exception types.
   * @param e the exception to check for compatibility.
   * @return the resulting compatible exception.
   */
  private Exception getMethodCompatibleException(final Method method, final Exception e) {
    Exception res = null;
    for (final Class<?> exceptionType : method.getExceptionTypes()) {
      if (exceptionType.isAssignableFrom(e.getClass())) {
        res = e;
        break;
      }
      if (res == null && exceptionType.isAssignableFrom(RemoteException.class)) {
        res = new RemoteException(e.getMessage(), e);
      }
    }
    if (res == null) {
      res = new RuntimeException(e.getMessage(), e);
    }
    return res;
  }

  private String toString(final Method method, final Object[] args) {
    return classType.getName() + "." + method.getName() + Arrays.toString(args); //$NON-NLS-1$
  }
}
