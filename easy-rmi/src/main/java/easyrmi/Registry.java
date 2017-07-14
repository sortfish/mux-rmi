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
package easyrmi;

import java.lang.reflect.Method;
import java.rmi.NotBoundException;
import java.util.HashMap;
import java.util.Map;

import easyrmi.Protocol.ClassRef;
import easyrmi.Protocol.MethodRef;

/**
 * A registry of methods and object references which are accessible remotely.
 * @author ReneAndersen
 */
class Registry {

  /** The callable methods of the API object */
  private final Map<String, Method> methods = new HashMap<>();

  /** A map with the callable object references */
  private final Map<String, Object> references = new HashMap<>();

  private final Registry parent;

  Registry(final Registry parentRegistry) {
    this.parent = parentRegistry;
  }

  Registry() {
    this.parent = null;
  }

  void init(final Registry initialRegistry) {
    methods.putAll(initialRegistry.methods);
    references.putAll(initialRegistry.references);
  }

  /**
   * Register a reference to the specified API object.
   * @param api the API object to register.
   */
  void registerReference(final ClassRef classRef, final Object api) {
    final Object previous = references.put(classRef.id(), api);
    if (previous != null && previous != api) {
      references.put(classRef.id(), previous);
      throw new IllegalArgumentException("Class reference already registered: " + classRef + " -> " + previous); //$NON-NLS-1$ //$NON-NLS-2$
    }
  }

  /**
   * Unregister an object reference.
   * @param classRef the ClassRef of the reference to remove.
   * @return {@code true} iff an object reference was found (and removed), {@code false} otherwise.
   */
  boolean unregisterReference(final ClassRef classRef) {
    return references.remove(classRef.id()) != null;
  }

  /**
   * Get a registered object reference if it exist.
   * @param id the ID of the reference to get.
   * @return the object with the specified id, or {@code null} if it doesn't exist.
   */
  Object findReference(final String id) {
    Object ref = references.get(id);
    if (ref == null && parent != null) {
      ref = parent.findReference(id);
    }
    return ref;
  }

  /**
   * Get the registered object reference.
   * @param id the ID of the reference to get.
   * @return the object with the specified id.
   * @throws NotBoundException if an object reference with the specified ID was not registered.
   */
  Object getReference(final String id) throws NotBoundException {
    final Object api = findReference(id);
    if (api != null) {
      return api;
    }
    throw new NotBoundException(id);
  }

  /**
   * @param apiClass
   */
  void registerMethods(final ClassRef apiClass) {
    for (final Method method : apiClass.classType.getMethods()) {
      methods.put(MethodRef.id(apiClass, method.getName()), method);
    }
  }

  boolean unregisterMethods(final ClassRef apiClass) {
    boolean status = true;
    for (final Method method : apiClass.classType.getMethods()) {
      status = methods.remove(MethodRef.id(apiClass, method.getName())) != null && status;
    }
    return status;
  }

  Method findMethod(final String methodId) {
    Method method = methods.get(methodId);
    if (method == null && parent != null) {
      method = parent.findMethod(methodId);
    }
    return method;
  }

  /**
   * Find a {@link Method} object for the specified method name.
   * @param methodId the method identifier.
   * @return the found method object.
   * @throws NotBoundException if no method is found for the specified method name.
   */
  Method getMethod(final String methodId) throws NotBoundException {
    final Method method = findMethod(methodId);
    if (method != null)
      return method;
    throw new NotBoundException(methodId + " in:\n" + toString()); //$NON-NLS-1$
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append("Registry [\n  methods=").append(methods) //$NON-NLS-1$
      .append(",\n  references=").append(references) //$NON-NLS-1$
      .append("]"); //$NON-NLS-1$
    if (parent != null)
      sb.append(",\n").append(parent.toString()); //$NON-NLS-1$
    return sb.toString();
  }
}
