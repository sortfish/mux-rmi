## Introduction to Easy-RMI

Easy-RMI is an easy-to-use alternative to standard Java RMI. Its main features compared to standard Java RMI are:

 * Support for in-channel callbacks, i.e. the possiblity to perform callbacks without having to create an additional network connection.
 * Increased reliability due to its in-protocol support for keep-alive packages, in order to keep idle network connections alive across network boundaries with short TCP idle timeouts.
 * Transparent scaling of number of open network connections, to accomodate the number of parallel remote method invocations.
 
### Easy-RMI vs Dirmi
 
In reality Easy-RMI was not created as a replacement for RMI, but as a simpler alternative to another RMI replacement: [Dirmi](https://github.com/cojen/Dirmi).

Dirmi is a great, full-featured bidirectional library which does everything you could possibly want from an RMI replacement.
I've used it myself for big client-server applications with 1000s of concurrent users distributed across the globe.
But in that context its design does have some limitations:

 * The use of multiple parallel network connections for each Dirmi session makes it vulnerable to load balancing issues.
 * Dirmi is vulnerable to network timeouts during long-running requests, even if the ping interval is lowered to prevent timeouts during periods of inactivity.
 
 The main differences in Easy-RMI compared to Dirmi are:
 
  * A simpler design with fewer tweakable configuration settings.
  * A much smaller code base makes issues easier to understand/debug.
 
 The main design limitation of Easy-RMI compared to Dirmi is:
 
  * It is not possible to pass persistent remote object references from a remote client to the server. Remote objects are valid only in the remote method invocation they are used in, and attempts to use them after the invocation has finished is an error.
  
