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

import static muxrmi.Protocol.State.ACCEPT;
import static muxrmi.Protocol.State.CLOSED;
import static muxrmi.Protocol.State.INITIAL;
import static muxrmi.Protocol.State.RUNNING;

import java.io.EOFException;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common client/server implementation of the remote connection protocol.
 * <p/>
 * The Mux-RMI protocol is inherently symmetric with each endpoint being
 * able to both send and receive function invocations. Therefore this
 * class contains methods to both invoke remote methods and to run an
 * event loop for reading and dispatching remote commands.
 * <p/>
 * An instance of this class is created as either a client or a server,
 * and can be top-level or embedded. An embedded protocol instance 
 * inherits the context of its parent protocol instance, and has access
 * to the same set of exposed remote object references etc:
 * <p/>
 * When a callback is performed from within a remote object invocation 
 * received by a protocol server instance, a new embedded remote client
 * instance is create to invoke the remote method. Similarly when a 
 * remote client receives a callback invocation response to a remote 
 * method invocation it will create a new embedded remote server
 * instance to handle the method invocation.
 * 
 * @author Rene Andersen
 */
abstract class Protocol implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(Protocol.class);

  /** The current state of a protocol instance. */
  enum State { INITIAL, ACCEPT, RUNNING, CLOSED }

  /** Shared state between nested protocol instances. */
  static class SharedState {
    volatile State state = INITIAL;
    volatile long lastUpdateMillis = System.currentTimeMillis();

    void update(final State newState) {
      state = newState;
      lastUpdateMillis = System.currentTimeMillis();
    }
  }

  SharedState getSharedState() {
    return ctx.state;
  }

  /**
   * The context of a protocol instances is a {@link Registry}, with the following additional elements:
   * <ul>
   * <li>A {@link Connection} object.</li>
   * <li>A {@link SharedState} object.</li>
   * </ul>
   * Like a registry, the context object can be either top-level or a child of a parent context.
   */
  private static class Context extends Registry {
    final Connection con;
    final SharedState state;
    final ClassLoader classLoader;

    /**
     * Create a new top-level context for the specified socket connection and class loader.
     * @param socket the connected socket.
     * @param classLoader the class loader.
     */
    Context(final Socket socket, final ClassLoader classLoader) {
      this.con = new Connection(socket, classLoader);
      this.state = new SharedState();
      this.classLoader = classLoader;
    }

    /**
     * Create a child context of the specified parent context. The connection, shared state 
     * and class loader of the new context will be taken from the parent context.
     * @param parent the parent context.
     */
    Context(final Context parent) {
      super(parent);
      this.con = parent.con;
      this.state = parent.state;
      this.classLoader = parent.classLoader;
    }
  }

  /**
   * Unique identity of a remote protocol instance.
   * <p/>
   * The unique identity consists of a sequence number which is assigned from
   * a global counter every time a new identity instance is created.
   * <p/>
   * This ID is printed in log statements to make it easy to track activity
   * of a specific protocol instance.
   */
  private static final class Identity {
    private static final AtomicLong ID = new AtomicLong();
    private final long id = ID.getAndIncrement();
    
    @Override
    public String toString() {
      return "Protocol#" + id;
    }
  }
  
  private final Set<Class<?>> remoteClasses = new HashSet<>();
  private final Identity identity = new Identity();
  private final Context ctx;
  private volatile boolean isClosed = false;

  /**
   * Create a new protocol instance with the specified context.
   * @param ctx the context.
   */
  private Protocol(final Context ctx) {
    this.ctx = ctx;

    logger.debug("{}", this); //$NON-NLS-1$
  }

  /**
   * Create a top-level protocol instance that communicates on the specified socket connection.
   * @param socket an accepted socket connection to the remote party.
   * @param classLoader The class loader to use when loading classes.
   */
  private Protocol(final Socket socket, final ClassLoader classLoader) {
    this(new Context(socket, classLoader));
  }
  
  /**
   * Specialization of {@link Protocol} for a client-side protocol instance.
   */
  static final class Client extends Protocol {    
    private final boolean topLevel;
    private String name;

    /**
     * Create a top-level client-side protocol instance on the specified socket connection.
     * @param socket the socket.
     * @param classLoader the class loader to use when loading classes.
     */
    public Client(final Socket socket, final ClassLoader classLoader) {
      super(socket, classLoader);
      this.topLevel = true;
    }

    /**
     * Create a new child client protocol instance based on the specified parent context.
     * @param parentContext the parent context.
     */
    public Client(final Context parentContext) {
      super(new Context(parentContext));
      super.ctx.state.update(INITIAL);
      this.topLevel = false;
    }

    @Override
    protected void handleError(final Exception e) throws Exception {
      throw e;
    }

    @Override
    protected void handleEnd(final boolean isInitiator) throws Exception {
      try {
        if (isInitiator) {
          write(Command.END);
          Object result;
          do {
            result = run();
          } while (result != Command.END);
        }
      } finally {
        disconnect();
      }
    }
    
    @Override
    protected String getName() {
      if (name == null) {
        synchronized(this) {
          if (name == null)
            name = "client, " + (topLevel ? "topLevel" : "nested");
        }
      }
      return name;
    }
    
    @Override
    void disconnect() {
      if (!topLevel) {
        logger.warn("{} Disconnecting from nested protocol instance", this);
      }
      super.disconnect();
    }
  }

  /**
   * Specialization of {@link Protocol} for a server-side protocol instance.
   */
  static final class Server extends Protocol {
    /**
     * Create a top-level server-side protocol instance on the specified socket connection.
     * @param socket the socket.
     * @param registry a {@link Registry} describing the methods which are available for remote invocation.
     * @param classLoader the class loader to use when loading classes.
     */
    public Server(final Socket socket, final Registry registry, final ClassLoader classLoader) {
      super(socket, classLoader);
      super.ctx.init(registry);
    }
    
    @Override
    protected Object handleResult() throws UnsupportedOperationException, Exception {
      // We do not accept RESULT requests to a server. But for error reporting purposes and to
      // clean up the input stream we read the result object before throwing an error.
      final Object result = super.handleResult();
      throw new UnsupportedOperationException("RESULT: " + result); //$NON-NLS-1$
    }

    @Override
    protected void handleError(final Exception e) throws Exception {
      super.handleError(e, true);
    }
    
    @Override
    protected void handleEnd(final boolean isInitiator) throws Exception {
      try {
        write(Command.END);
      } finally {
        disconnect();
      }
    }    
    
    @Override
    protected String getName() {
      return "server";
    }
  }
  
  /**
   * Run the protocol command loop until a result is available.
   * <p/>
   * The result will be the return value from a method call, or the terminating {@link Command} value:
   * <ul>
   * <li>{@link Command#OK} if a method call completed without a return value</li>
   * <li>{@link Command#END} if the remote client terminated the connection.</li>
   * </ul>
   *
   * @return the result value.
   * @throws Exception if the command loop terminates with an error.
   */
  Object run() throws Exception {
    try {
      return runCommandLoop();
    } catch (final InvocationTargetException e) {
      // Exception thrown by a local method invocation.
      throw e;
    } catch (final IOException e) {
      logger.debug("{} I/O error: {}", identity, e.toString()); //$NON-NLS-1$
      throw e;
    } catch (final NotBoundException e) {
      logger.debug("{} Not bound: {}", identity, e.getMessage()); //$NON-NLS-1$
      throw e;
    } catch (final Throwable cause) {
      logger.error(this + " Unhandled exception", cause); //$NON-NLS-1$
      throw cause;
    }
  }

  /**
   * Check the the specified class is registered remotely.
   * @param classType the class type to check.
   * @throws NotBoundException if the class was not registered remotely.
   * @throws Exception any exception thrown during the remote call.
   */
  void bind(final Class<?> classType) throws NotBoundException, Exception {
    invokeRemote(Command.BIND, ClassRef.forClass(classType));
  }

  /**
   * Called to perform a remote method invocation.
   * @param classRef a {@link ClassRef} for the class of the invoked method.
   * @param method the {@link Method} to invoke.
   * @return the return value from the method invocation.
   * @throws NotBoundException if the method or its class reference was not registered remotely.
   * @throws InvocationTargetException wrapping any exception thrown by the method.
   * @throws Exception any other exception that occurred during the method invocation.
   */
  Object invokeRemote(final ClassRef classRef, final Method method, final Object[] args) throws InvocationTargetException, Exception {
    return invokeRemote(Command.CALL, outgoingCall(classRef, method, args));
  }

  /**
   * Send a continuation command to the remote party.
   * @throws IOException if the command could not be written.
   */
  void sendContinue() throws IOException {
    write(Command.CONTINUE);
  }

  /**
   * Create a proxy stub for the specified remote API. The class reference must be to an interface type.
   * @param apiClass the remote API interface class.
   * @return a proxy stub for the remote API.
   * @throws InvalidClassException if the specified class does not implement the {@link Remote} interface.
   */
  <API> API createProxy(final Class<API> apiClass) throws InvalidClassException {
    return createProxy(ClassRef.forClass(apiClass));
  }

  /**
   * @return A new instance of {@link ProxyObjectFactory} for creating local proxy objects for remote interfaces.
   */
  ProxyObjectFactory newProxyObjectFactory() {
    return new ProxyObjectFactory();
  }

  /**
   * A proxy object factory for creating local proxy objects for remote class types.
   * <p/>
   * The class loader and invocation handler instances used by this factory can be
   * customized via the methods {@link #withClassLoader(ClassLoader)} and 
   * {@link #withInvocationHandler(InvocationHandler)}.
   * <p/>
   * If {@link #create(Class)} is called on an instance where either of these have not
   * been set then a default instance of the missing class is created from the current 
   * protocol context.
   */
  final class ProxyObjectFactory {
    private ClassLoader classLoader;
    private InvocationHandler invocationHandler;

    ProxyObjectFactory withClassLoader(final ClassLoader newClassLoader) {
      this.classLoader = newClassLoader;
      return this;
    }

    /**
     * Set the invocation handler for this proxy object factory instance.
     * @param newInvocationHandler the new invocation handler.
     * @return {@code this} proxy object factory.
     */
    ProxyObjectFactory withInvocationHandler(final InvocationHandler newInvocationHandler) {
      this.invocationHandler = newInvocationHandler;
      return this;
    }

    /**
     * Create a proxy object for the specified class type.
     * @param classType the class type.
     * @return the resulting proxy object.
     */
    <API> API create(final Class<API> classType) {
      if (invocationHandler == null) invocationHandler = new ProxyInvocationHandler(ClassRef.forClass(classType), ctx);
      if (classLoader == null) classLoader = new RemoteProxyClassLoader(ctx.classLoader, classType.getClassLoader(), getClass().getClassLoader());
      return createProxy(classLoader, invocationHandler, classType, ProxyClass.class);
    }
  }

  /**
   * @return {@code true} iff we have a connected socket reference, {@code false} otherwise.
   */
  boolean isConnected() {
    return ctx.con.isConnected();
  }

  /**
   * Forcible disconnect this protocol instance.
   */
  void disconnect() {
    if (!isClosed) {
      ctx.con.close();
      ctx.state.update(CLOSED);
      isClosed = true;
      logger.debug("{} Disconnected", identity);
    }
  }

  /**
   * @return {@code true} iff {@link #close()} has been called, {@code false} otherwise. 
   */
  boolean isClosed() {
    return isClosed;
  }
  
  /**
   * Close this protocol instance and release all network resources - but only if we're a top-level instance.
   */
  @Override
  public synchronized void close() {
    if (isClosed) return;
    
    logger.debug("{} Closed", identity); //$NON-NLS-1$
    try {
      if (ctx.isTopLevel()) {
        if (isConnected()) {
          handleEnd(true);
        }
      }
    } catch (final Exception e) {
      if (logger.isDebugEnabled()) logger.debug(identity + " Error in close", e); //$NON-NLS-1$
    } finally {
      isClosed = true;
    }
  }

  /**
   * @return a short string that unique identifies this protocol instance.
   */
  public String id() {
    return identity.toString();
  }
  
  @Override
  public String toString() {
    return id() + " ["
         + getName()
         + ", connection=" + ctx.con + "]";
  }

  @Override
  protected void finalize() {
    if (!isClosed())
      if (logger.isWarnEnabled()) logger.warn("{} Closing in finalizer", this);
      close();
  }

  /**
   * Enumeration with the legal protocol commands.
   */
  enum Command {
    /** A continuation command which does not require a response */
    CONTINUE(0),
    /** A confirmation with no additional data, e.g. the result of calling a method returning 'void' */
    OK(1),
    /** An error response, followed by: {@link Exception} */
    ERROR(2),
    /** A service bind request, followed by: classRef:{@link ClassRef} */
    BIND(3),
    /** A method call command, followed by: methodRef:{@link MethodRef} */
    CALL(4),
    /** A result value for the last command: {@link Object}(result) */
    RESULT(5),
    /** The final command, used to terminate the connection. */
    END(6);

    private final int command;

    private Command(final int command) {
      this.command = command;
    }

    /**
     * @return the byte value (as an int) of this command.
     */
    public int get() {
      return command;
    }

    static final Command[] COMMANDS = Command.values();

    /**
     * @param command the byte value (as an int) of the command.
     * @return the {@link Command} instance corresponding to the byte value.
     * @throws EOFException if the command value is an EOF value (-1).
     */
    public static Command get(final int command) throws EOFException {
      if (command >= 0 && command < COMMANDS.length) {
        return COMMANDS[command];
      }
      if (command == -1) {
        throw new EOFException("EOF"); //$NON-NLS-1$
      }
      throw new IllegalArgumentException("Illegal command value: " + command); //$NON-NLS-1$
    }
  }

  /**
   * Signature interface for a proxy class reference. Instances of this interface are replaced
   * by a {@link RemoteStub} when serialized.
   */
  public interface ProxyClass {
    /**
     * @return the ClassRef of the remote class.
     */
    ClassRef getClassRef();
  }

  /**
   * Invocation handler on a local proxy stub for a remote class reference.
   * <p/>
   * When a remote method is invoked a new child protocol instance is created from
   * the current context, and the method invocation is performed on that protocol
   * instance.
   */
  static class ProxyInvocationHandler extends RemoteInvocationHandler implements ProxyClass {
    private final ClassRef classRef;
    private final Context context;

    protected ProxyInvocationHandler(final ClassRef classRef, final Context context) {
      super(classRef.classType);
      this.classRef = classRef;
      this.context = context;
    }

    /** {@inheritDoc} */
    @Override
    public ClassRef getClassRef() {
      return classRef;
    }

    @Override
    protected Object invokeRemote(final Method method,
                                  final Object[] args) throws InvocationTargetException, Exception {
      final Object result;
      try (final Protocol protocol = new Client(context)) {
        result = protocol.invokeRemote(classRef, method, args);
      }
      return result;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + " [classRef=" + classRef + "]"; //$NON-NLS-1$ //$NON-NLS-2$
    }
  }

  /**
   * Placeholder stub for a remote class.
   */
  static final class RemoteStub implements Serializable {
    private static final long serialVersionUID = 1L;

    final ClassRef remoteClass;

    RemoteStub(final ClassRef remoteClass) {
      this.remoteClass = remoteClass;
    }

    @Override
    public String toString() {
      return "RemoteStub:" + remoteClass; //$NON-NLS-1$
    }
  }

  /**
   * Object representing a reference to a remote class or class instance.
   */
  public static final class ClassRef implements Serializable {
    private static final long serialVersionUID = 1L;

    final Class<?> classType;
    final Integer instanceId;

    ClassRef(final Class<?> classType, final Integer instanceId) {
      this.classType = classType;
      this.instanceId = instanceId;

      if (!classType.isInterface())
        throw new IllegalArgumentException(classType + " is not an interface"); //$NON-NLS-1$
    }

    static ClassRef forClass(final Class<?> classType) {
      return new ClassRef(classType, null);
    }

    static ClassRef forInstance(final Class<?> classType, final Object instance) {
      return new ClassRef(getInterfaceType(classType, instance.getClass()), instance.hashCode());
    }

    static Class<?> getInterfaceType(final Class<?> classType, final Class<?> instanceType) {
      if (classType.isInterface()) {
        return classType;
      }
      final List<Class<?>> instanceInterfaces = Arrays.asList(instanceType.getInterfaces());
      for (final Class<?> i : classType.getInterfaces()) {
        if (Remote.class.isAssignableFrom(i)) {
          return i;
        }
      }
      for (final Class<?> i : instanceInterfaces) {
        if (Remote.class.isAssignableFrom(i)) {
          return i;
        }
      }
      throw new IllegalArgumentException("No remote interface found for object with class type '" + classType +  //$NON-NLS-1$
                                         "' and instance type '" + instanceType + "'"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    String id() {
      return classType + (instanceId != null ? "@" + instanceId : "");  //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
      return "ClassRef [" + id() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
    }
  }

  /**
   * Object representing an invocation of a remote method.
   */
  static final class MethodRef implements Serializable {
    private static final long serialVersionUID = 1L;

    private final ClassRef classRef;
    private final String methodName;
    private final Object[] args;

    MethodRef(final ClassRef classRef, final Method method, final Object[] args) {
      this.classRef = classRef;
      this.methodName = method.getName();
      this.args = args;
    }

    static String id(final ClassRef classRef, final String methodName) {
      return classRef.id() + "#" + methodName; //$NON-NLS-1$
    }

    String id() {
      return id(classRef, methodName);
    }

    @Override
    public String toString() {
      return "MethodRef [" + id() + "(" + Arrays.toString(args) + ")]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
  }

  /**
   * Read the next {@link Command} from the input stream.
   * @return the command.
   * @throws Exception if a command could not be read.
   */
  protected final Protocol.Command read() throws Exception {
    ctx.state.update(ACCEPT);
    final int cmd = ctx.con.in().read();
    ctx.state.update(RUNNING);
    final Command command = Command.get(cmd);

    if (logger.isTraceEnabled()) logger.trace("{} <- {}", identity, command); //$NON-NLS-1$
    return command;
  }

  /**
   * Read the next {@link Object} from the input stream.
   * @return the object.
   * @throws Exception (IOException | ClassCastException) if an object could not be read
   */
  protected final Object readObject() throws Exception {
    final Object res = ctx.con.in().readObject();

    if (logger.isTraceEnabled()) logger.trace("{} <- {}", identity, res); //$NON-NLS-1$
    return res;
  }

  /**
   * Write a {@link Command} and zero or more arguments to the output stream.
   * @param command the command.
   * @param args the arguments.
   * @throws IOException if the command could not be written.
   */
  protected final synchronized void write(final Protocol.Command command, final Object... args) throws IOException {
    if (logger.isTraceEnabled()) logger.trace("{} -> {} {}", new Object[] {identity, command, Arrays.toString(args)}); //$NON-NLS-1$

    final ObjectOutputStream out = ctx.con.out();
    out.reset();
    out.write(command.get());
    for (final Object arg : args) {
      out.writeObject(arg);
    }
    out.flush();
    ctx.state.update(RUNNING);
  }

  private void handleError(final Exception e, final boolean handleRemotely) throws Exception {
    if (handleRemotely) {
      try {
        write(Command.ERROR, e);
      } catch (final Exception e2) {
        if (logger.isErrorEnabled()) {
          final String msg = this + " Error sending ERROR response: " + e;
          if (logger.isDebugEnabled())
            logger.debug(msg, e2);
          else
            logger.error("{}: {}", msg, e2.toString());
          throw e2;
        }
      }
    } else {
      throw e;
    }
  }
  
  /**
   * Read a {@link ClassRef} and verify that it corresponds to a registered class reference.
   * @throws NotBoundException if the received class reference was not registered.
   * @throws Exception if an error occurs while reading this request.
   */
  protected void handleBind() throws NotBoundException, Exception {
    final ClassRef classRef = (ClassRef) readObject();
    ctx.getReference(classRef.id());
    write(Command.OK);
  }

  /**
   * Read and invoke a method call from the input stream.
   * @throws Exception if a method call could not be read.
   */
  protected void handleCall() throws Exception {
    final MethodRef methodRef = (MethodRef) readObject();
    try {
      sendResult(invokeLocal(incomingCall(methodRef)));
    } catch (final Exception e) {
      handleError(e, true);
    }
  }

  /**
   * Read the RESULT command by reading a result from the input stream and handle it.
   * @return the result.
   * @throws UnsupportedOperationException if a result was not expected in this context.
   * @throws Exception if an object could not be read.
   */
  protected Object handleResult() throws UnsupportedOperationException, Exception {
    return readObject();
  }

  /**
   * Read an {@link Exception} from the input stream.
   * @return the exception.
   * @throws Exception if an exception could not be read.
   */
  protected Exception handleError() throws Exception {
    final Throwable t = (Throwable) readObject();
    if (t instanceof Exception) {
      return (Exception) t;
    }
    return new Exception(t);
  }

  /**
   * Called when an error occurs locally which should be sent to the remote protocol instance.
   * @param e the error.
   * @throws Exception the error if it should be re-thrown locally, or any other exception that occurred while handling the
   *                   exception.
   */
  protected abstract void handleError(final Exception e) throws Exception;

  /**
   * Handle the END command and disconnect this protocol instance.
   * @param isInitiator {@code true} if the END command was initiated locally, {@code false} if it was initiated remotely.
   * @throws Exception if thrown while disconnecting.
   */
  protected abstract void handleEnd(final boolean isInitiator) throws Exception;

  /**
   * @return a short textual description of this protocol instance, for use in {@link #toString()}.
   */
  protected abstract String getName();
  
  /**
   * Read and execute commands from the remote protocol instance until a result is available
   * or an error occurs.
   * @return the result.
   * @throws Exception if the command loop terminates with an error.
   */
  private Object runCommandLoop() throws Exception {
    do {
      final Command command = read();
      try {
        switch (command) {
          case CONTINUE:
            break;
  
          case OK:
            return command;
  
          case ERROR:
            final Exception e = handleError();
            if (e != null) throw e;
            break;
  
          case BIND:
            handleBind();
            break;
  
          case CALL:
            handleCall();
            break;
  
          case RESULT:
            return handleResult();
  
          case END:
            handleEnd(false);
            return command;
        }
      } catch (final Exception e) {
        handleError(e);
      }
    } while (true);
  }

  /**
   * Invoke a method reference locally and return the outgoing result.
   * @param methodRef the method reference to invoke.
   * @return the outgoing result, with proxy classes replaced by remote stubs.
   * @throws NotBoundException if the specified method or its class reference isn't in the current context.
   * @throws InvocationTargetException if an exception is thrown by the invoked method.
   * @throws Exception if an error occurs invoking the method.
   */
  private Object invokeLocal(final MethodRef methodRef) throws NotBoundException, InvocationTargetException, Exception {
    final Object api = ctx.getReference(methodRef.classRef.id());
    final Method method = ctx.getMethod(methodRef.id());
    final Object result = method.invoke(api, methodRef.args);
    return outgoing(result, method.getReturnType());
  }

  /**
   * Invoke a remote command and return the received result.
   * @param command the {@link Command} to invoke.
   * @param obj the object argument for the command, or {@code null} if no argument should be sent.
   * @return the received result.
   * @throws Exception if an error occurs while invoking the remote command.
   */
  private Object invokeRemote(final Command command, final Object obj) throws Exception {
    if (obj != null) {
      write(command, obj);
    } else {
      write(command);
    }
    final Object result = run();
    if (result instanceof Command) {
      switch ((Command) result) {
        case OK:
          return null;
        case END:
          throw new IOException("Remote session ended"); //$NON-NLS-1$
        default:
          throw new IOException("Unexpected result value: " + result); //$NON-NLS-1$
      }
    }
    return incoming(result);
  }

  /**
   * Send a result to the remote protocol instance.
   * @param result the result to send.
   * @throws IOException if the result could not be sent.
   */
  private void sendResult(final Object result) throws IOException {
    if (result != null) {
      write(Command.RESULT, result);
    } else {
      write(Command.OK);
    }
  }

  /**
   * Create an incoming method call representation. Any argument that matches the remote stub placeholder will be replace by
   * a proxy stub for the remote object.
   * @param methodName the method name.
   * @param args the argument array.
   * @return the incoming method call representation.
   */
  private MethodRef incomingCall(final MethodRef methodRef) {
    if (methodRef.args != null) {
      int i = 0;
      for (final Object arg : methodRef.args) {
        methodRef.args[i] = incoming(arg);
        ++i;
      }
    }
    return methodRef;
  }

  /**
   * Create an outgoing method call representation. Any argument that matches a registered remote object type will be replaced by
   * a remote stub placeholder object.
   * @param methodName the method name.
   * @param args the argument array.
   * @return the outgoing method call representation.
   */
  private MethodRef outgoingCall(final ClassRef classRef, final Method method, final Object[] args) {
    if (args != null) {
      final Class<?>[] parameterTypes = method.getParameterTypes();
      int i = 0;
      try {
        for (final Object arg : args) {
          args[i] = outgoing(arg, parameterTypes[i]);
          ++i;
        }
      } catch (final Exception e) {
        throw new IllegalArgumentException("[" + (i+1) + "] " + args[i], e); //$NON-NLS-1$ //$NON-NLS-2$
      }
    }
    return new MethodRef(classRef, method, args);
  }

  /**
   * Process an incoming object. If the object is a remote stub it is replaced by either a proxy stub,
   * or the local object if the remote stub object refers to a local object instance.
   * @param obj the incoming object to process.
   * @return the resulting local object.
   */
  private Object incoming(final Object obj) {
    if (obj instanceof RemoteStub) {
      final RemoteStub remoteStub = (RemoteStub) obj;
      final Object localInstance = ctx.findReference(remoteStub.remoteClass.id());
      if (localInstance != null) {
        return localInstance;
      } else {
        return createProxy(remoteStub.remoteClass);
      }
    }
    return obj;
  }

  /**
   * Process an outgoing object. If the object is a proxy class or its class type is a registered remote class
   * it is replaced by a remote stub.
   * @param obj the outgoing object to process.
   * @param classType the formal class type of the outgoing object.
   * @return the resulting outgoing object.
   */
  private Object outgoing(final Object obj, final Class<?> classType) {
    if (obj instanceof ProxyClass) {
      final ClassRef remoteClassRef = ((ProxyClass) obj).getClassRef();
      return new RemoteStub(remoteClassRef);
    }
    if (obj != null && isRemote(obj.getClass())) {
      final ClassRef remoteClassRef = ClassRef.forInstance(classType, obj);
      ctx.registerReference(remoteClassRef, obj);
      ctx.registerMethods(remoteClassRef);
      return new RemoteStub(remoteClassRef);
    }
    return obj;
  }

  /**
   * Check whether the specified {@link Class} type or one of its super-types is registered as a remote class.
   * <p/>
   * If the class type is not registered but one of its super-types are, then the class type will itself be registered
   * to speed up future look-ups.
   * @param localClass the class type to check.
   * @return {@code true} iff the class or one of its super-types is registered as a remote class, {@code false} otherwise.
   */
  private boolean isRemote(final Class<?> localClass) {
    if (remoteClasses.contains(localClass)) {
      return true;
    }
    if (Remote.class.isAssignableFrom(localClass)) {
      setRemote(localClass);
      return true;
    }
    for (final Class<?> remoteClass : remoteClasses) {
      if (remoteClass.isAssignableFrom(localClass)) {
        setRemote(localClass);
        return true;
      }
    }
    return false;
  }

  /**
   * Register the specified {@link Class} type as a remote class.
   * @param remoteClass the remote class type.
   */
  private void setRemote(final Class<?> remoteClass) {
    remoteClasses.add(remoteClass);
    if (logger.isTraceEnabled()) logger.trace("Remotes: {} -> {}", remoteClass, remoteClasses);
  }

  /**
   * Create a proxy stub for the specified class reference. The class reference must be to an interface type.
   * <p/>
   * A class loader is created for the proxy stub which delegates to the following class loaders
   * (in prioritized order):
   * <ul>
   * <li>The current context class loader</li>
   * <li>The class loader of the class reference</li>
   * <li>The class loader of {@link Protocol}</li>
   * </ul>
   * @param classRef the class reference.
   * @return a proxy stub for a remote class.
   */
  private <API> API createProxy(final ClassRef classRef) {
    final Class<?> classType = classRef.classType;
    final RemoteProxyClassLoader classLoader = new RemoteProxyClassLoader(ctx.classLoader,
                                                                          classType.getClassLoader(),
                                                                          getClass().getClassLoader());
    return createProxy(classLoader,
                       new ProxyInvocationHandler(classRef, ctx),
                       new Class[] {classType, ProxyClass.class});
  }

  /**
   * Create a proxy stub for the specified list of interfaces using the specified class loader and invocation
   * handler.
   * @param classLoader the class loader to use.
   * @param invocationHandler the invocation handler.
   * @param interfaces the list of interfaces for the proxy class to implement. 
   * @return the resulting proxy class.
   */
  @SuppressWarnings("unchecked")
  private static <API> API createProxy(final ClassLoader classLoader,
                                       final InvocationHandler invocationHandler,
                                       final Class<?>... interfaces) {
    for (final Class<?> classType : interfaces) {
      if (!classType.isInterface()) {
        throw new IllegalArgumentException(classType + " is not an interface"); //$NON-NLS-1$
      }
    }
  
    return (API) Proxy.newProxyInstance(classLoader, interfaces, invocationHandler);
  }

}

