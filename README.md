Networks3334 — Load Balancer, Server, Client

Overview
- LoadBalancer (TCP 11114 for clients, TCP 11115 for server registrations)
- Server (TCP 11112 for commands; UDP 11113 for streaming)
- Client (connects to LoadBalancer, then to a selected Server)

How load balancing works
- Static (default): round‑robin assignment across all registered servers.
- Dynamic: the LoadBalancer pings each server (sending "ping", expects "pong") and selects the one with the lowest measured latency. If no server replies, it falls back to static.

Run order
1) Start the LoadBalancer class from your IDE (or java LoadBalancer). You should see: "Load Balancer running on ports 11114 and 11115".
2) Start one or more Server instances from your IDE (or java Server). Each server automatically registers to the LoadBalancer at localhost:11115 and listens on TCP 11112. Console shows: "Successfully registered with Load Balancer." and "Server listening on port: 11112".
   - Note: The sample Server uses fixed ports. To run multiple servers on one machine, change PORT/UDP_PORT in Server.java or run them on different hosts.
3) Start Client instances. The client first contacts the LoadBalancer, then connects to the assigned Server.

Client usage (name and mode)
- Mode controls whether the LoadBalancer assigns the server statically or dynamically.
- Name is a human‑readable ID shown on the LoadBalancer and Server logs.

Syntax:
  java Client [--name NAME | -n NAME] [--mode static|dynamic | -m static|dynamic]

Defaults:
- If --name is omitted, a unique name like "Client-<8chars>" is auto‑generated.
- If --mode is omitted or not "dynamic", the client uses static mode by default.

Examples
- Static (default):
  java Client -n Alice -m static
- Dynamic (lowest latency):
  java Client --name Bob --mode dynamic
- Let the client auto‑name in dynamic mode:
  java Client -m dynamic

What you should see
- LoadBalancer: "Client 'Alice' (dynamic) directed to: <ip>:11112".
- Server: on connect, you’ll see "Client identified as: Alice from <remote>" and the server replies "Hello, Alice!" after the client sends the initial hello.

Available client commands (after connection to a Server)
- pwd    — current working directory on the server
- ls     — list files
- sendfile <filename>
- time   — server time
- compute <seconds> — keep server busy for N seconds
- stream — start UDP streaming (1 KB packets, limited duration)
- cancel — stop streaming
- quit   — disconnect the client

Notes
- The Server registers to a LoadBalancer on localhost. To use a remote LoadBalancer, adjust LOAD_BALANCER_HOST in Server.java.
- Dynamic mode relies on the Server replying "pong" to "ping" (already implemented).
