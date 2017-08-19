# Introduction to Mux-RMI

Mux-RMI is an easy-to-use 2-way multiplexed replacement for Java RMI. Its main features compared to Java RMI are:

 * Support for in-channel callbacks, i.e. the possiblity to perform callbacks without having to create an additional network connection.
 * Increased reliability due to its in-protocol support for keep-alive packages, in order to keep idle network connections alive across network boundaries with short TCP idle timeouts.
 * Transparent scaling of number of open network connections, to accomodate any number of parallel remote method invocations.

# License

Mux-RMI is licensed under the MIT license. See the `LICENSE` file for details.

# Philosophy of Easy-RMI

Mux-RMI has been designed with the following goals in mind:

 1. It should be light weight and easy to use.
 2. It should behave as similar to a request-response protocol (HTTP, REST, ...) as possible, i.e. each request should require only one connection.
 3. It should be stateless in order to work on load balanced systems.
 4. It should conserve network resources, i.e. reuse connections when possible, close them when not needed.
 5. It should work well on unreliable wide-area networks, i.e. never let an open connection sit idle.

## Mux-RMI vs Dirmi
 
In reality Mux-RMI was not created as a replacement for Java RMI, but as a simpler alternative to another Java RMI replacement: [Dirmi](https://github.com/cojen/Dirmi).

Dirmi is a great, full-featured bidirectional RMI library which does everything you could possibly want from an Java RMI replacement.
I've used it myself for big client-server applications with 1000s of concurrent users distributed across the globe.
But in that context its design does have some limitations:

 * Dirmi is stateful and vulnerable to load balancing issues due to the use of multiple parallel network connections for each Dirmi session.
 * Dirmi is also vulnerable to network timeouts during long-running requests, even if the ping interval is lowered to prevent timeouts during periods of inactivity.
  
 The main design limitations of Mux-RMI compared to Dirmi are:
 
  * A remote reference can only be created for an interface type, not for a class type. The interface must extend `java.rmi.Remote`.
  * A remote reference can only be created for top-level method arguments, not for inner class member types.
  * A remote reference is only valid for the duration of the method invocation it appears in. It is not possible to pass persistent remote object references from a remote client to the server.
