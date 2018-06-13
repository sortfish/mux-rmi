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
package muxrmi.io;

import java.io.Closeable;
import java.io.IOException;

import muxrmi.Identity;

/**
 * This interface represents a communication channel to a remote endpoint.
 * 
 * @author Rene Andersen
 */
public interface CommunicationChannel extends AutoCloseable {
  /**
   * Interface for a connection factory. 
   */
  public interface Factory extends Closeable {
    /**
     * Create a new communication channel.
     * @return the new instance.
     * @throws IOException if the channel could not be created
     */
    CommunicationChannel create() throws IOException;
    
    /**
     * Close this factory and release all associated resources. Once closed
     * the factory is no longer able to create communication channels, and
     * calling {@link #create()} should result in an exception.
     */
    @Override
    void close() throws IOException;

    /**
     * @return {@code true} if this factory has been closed,
     *         {@code false} otherwise.
     */
    boolean isClosed();
  }
  
  /**
   * @return the identity of this connection.
   */
  Identity id();
  
  /**
   * @return the class loader associated with this connection.
   */
  ClassLoader getClassLoader();
  
  /**
   * @return {@code true} if the connection to the remote endpoint is open,
   *         {@code false} otherwise.
   */
  boolean isConnected();
  
  /**
   * Read and return the next command from the remote endpoint. The method must
   * block until a command is received or a communication error occurs.
   * @return the received command.
   * @throws IOException if a communication error occurs.
   */
  Command readCommand() throws IOException;
  
  /**
   * Read and return the next object read from the remote endpoint. This method
   * must block until an object is received or a communication error occurs.
   * @return the received object.
   * @throws IOException if a communication error occurs.
   * @throws ClassNotFoundException if the received class name is not found.
   */
  Object readObject() throws IOException, ClassNotFoundException;
  
  /**
   * Write a command to the remote endpoint.
   * @param command the command to write.
   * @throws IOException if a communication error occurs.
   */
  void writeCommand(Command command) throws IOException;
  
  /**
   * Write an object to the remote endpoint.
   * @param object the object to write.
   * @throws IOException if a communication error occurs.
   */
  void writeObject(Object object) throws IOException;
  
  /**
   * Flush the connection to the remote endpoint to ensure that all pending data
   * has been sent.
   * @throws IOException if a communication error occurs.
   */
  void flush() throws IOException;

  /**
   * Close the connection to the remote endpoint.
   * @throws IOException if a communication error occurs.
   */
  @Override
  void close() throws IOException;
}
