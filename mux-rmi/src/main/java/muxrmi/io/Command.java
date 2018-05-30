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

import java.io.EOFException;

/**
 * Enumeration with the legal protocol commands.
 * 
 * @author Rene Andersen
 */
public enum Command {
  /** A continuation command which does not require a response */
  CONTINUE(0),
  /** A confirmation with no additional data, e.g. the result of calling a method returning 'void' */
  OK(1),
  /** An error response, followed by: Exception */
  ERROR(2),
  /** A service bind request, followed by: ClassRef */
  BIND(3),
  /** A method call command, followed by: MethodRef */
  CALL(4),
  /** A result value, followed by: Object */
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