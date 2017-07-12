# Introduction to Easy-RMI

Easy-RMI is an easy-to-use replacement for standard Java RMI. Its main features compared to standard Java RMI are:

 * Support for in-channel callbacks, i.e. the possiblity to perform callbacks without having to create an additional network connection.
 * Increased reliability due to its in-protocol support for keep-alive packages, in order to keep idle network connections alive across network boundaries with short TCP idle timeouts.
 * Transparent scaling of number of open network connections, to accomodate any number of parallel remote method invocations.

# License

Easy-RMI is licensed under the MIT license. See the `LICENSE` file for details.

# Philosophy of Easy-RMI

Easy-RMI has been designed with the following goals in mind:

 1. It should be light weight, easy to use, and easy to understand.
 2. It should behave as similar to a request-response protocol (HTTP, REST, ...) as possible, i.e. each request should require only one connection.
 3. It should be stateless in order to work on load balanced systems.
 4. It should conserve network resources, i.e. reuse connections when possible, close them when not needed.
 5. It should work well on unreliable wide-area networks, i.e. never let an open connection sit idle.

## Easy-RMI vs Dirmi
 
In reality Easy-RMI was not created as a replacement for RMI, but as a simpler alternative to another RMI replacement: [Dirmi](https://github.com/cojen/Dirmi).

Dirmi is a great, full-featured bidirectional RMI library which does everything you could possibly want from an Java RMI replacement.
I've used it myself for big client-server applications with 1000s of concurrent users distributed across the globe.
But in that context its design does have some limitations:

 * The use of multiple parallel network connections for each Dirmi session makes it vulnerable to load balancing issues.
 * Dirmi is also vulnerable to network timeouts during long-running requests, even if the ping interval is lowered to prevent timeouts during periods of inactivity.
 
 The main differences in Easy-RMI compared to Dirmi are:
 
  * A simpler design with fewer tweakable configuration settings.
  * A much smaller code base makes issues easier to understand and debug.
 
 The main design limitations of Easy-RMI compared to Dirmi are:
 
  * A remote reference can only be created for an interface type, not for a class type. The interface must extend `java.rmi.Remote`.
  * A remote reference can only be created for top-level method arguments, not for inner class member types.
  * A remote reference is only valid for the duration of the method invocation it appears in. It is not possible to pass persistent remote object references from a remote client to the server.
