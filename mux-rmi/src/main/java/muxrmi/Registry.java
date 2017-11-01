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

import java.lang.reflect.Method;
import java.rmi.NotBoundException;
import java.util.HashMap;
import java.util.Map;

import muxrmi.Protocol.ClassRef;
import muxrmi.Protocol.MethodRef;

/**
 * A registry of methods and object references which are accessible in a specific remote context.
 * <p/>
 * The registry consists of two things:
 * <ul>
 * <li>A mapping of named methods which can be invoked remotely</li>
 * <li>A mapping of named references to remote object instances</li>
 * </ul>
 * A registry can be created in the context of a "parent" registry. In this case all methods and
 * object references from the parent registry are also available in the "child" registry.
 * @author Rene Andersen
 */
class Registry {

  /** The callable methods of the API object */
  private final Map<String, Method> methods = new HashMap<>();

  /** A map with the callable object references */
  private final Map<String, Object> references = new HashMap<>();

  /** The (optional) parent registry */
  private final Registry parent;

  /**
   * Create a new registry with the specified parent registry.
   * @param parentRegistry the parent registry.
   */
  Registry(final Registry parentRegistry) {
    this.parent = parentRegistry;
  }

  /**
   * Create a new top-level registry.
   */
  Registry() {
    this.parent = null;
  }

  /**
   * Initialize this registry by copying all method and object references from another "initial" registry.
   * @param initialRegistry the initial registry to copy from.
   */
  void init(final Registry initialRegistry) {
    methods.putAll(initialRegistry.methods);
    references.putAll(initialRegistry.references);
  }
  
  /**
   * @return {@code true} iff this is a top-level context (with no parent context).
   */
  boolean isTopLevel() {
    return parent == null;
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
   * Register all methods of a class reference in the method registry.
   * @param classRef the class reference.
   */
  void registerMethods(final ClassRef classRef) {
    for (final Method method : classRef.classType.getMethods()) {
      methods.put(MethodRef.id(classRef, method.getName()), method);
    }
  }

  /**
   * Unregister all methods of a class reference and remove them from this registry.
   * @param classRef the class reference.
   * @return {@code true} iff all methods in the class reference were successfully removed, {@code false} otherwise.
   */
  boolean unregisterMethods(final ClassRef classRef) {
    boolean status = true;
    for (final Method method : classRef.classType.getMethods()) {
      status = methods.remove(MethodRef.id(classRef, method.getName())) != null && status;
    }
    return status;
  }

  /**
   * Find a {@link Method} object for the specified method ID.
   * @param methodId the ID of the method to find, as created by {@link MethodRef#id(ClassRef, String)}.
   * @return the method with the specified ID, or {@code null} if it doesn't exist.
   */
  Method findMethod(final String methodId) {
    Method method = methods.get(methodId);
    if (method == null && parent != null) {
      method = parent.findMethod(methodId);
    }
    return method;
  }

  /**
   * Find a {@link Method} object for the specified method ID.
   * @param methodId the method ID, as created by {@link MethodRef#id(ClassRef, String)}.
   * @return the method with the specified ID.
   * @throws NotBoundException if no method is found for the specified method ID.
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
