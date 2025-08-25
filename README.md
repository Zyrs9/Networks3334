# Java TCP/UDP Load Balancer Demo

A minimal, production-inspired load balancer with **static round-robin** and **dynamic (RTT-based)** server selection, **multi-instance servers**, **UDP “streaming” side-channel**, **Base64 file transfer**, and a built-in **admin console** to observe and control the cluster live.

---

## Table of Contents

- [Architecture](#architecture)
- [Features](#features)
- [Ports & Protocols](#ports--protocols)
- [Build](#build)
- [Run](#run)
  - [Load Balancer](#load-balancer)
  - [Server (multi-instance)](#server-multi-instance)
  - [Client](#client)
- [Admin Console (LB)](#admin-console-lb)
- [Wire Protocols](#wire-protocols)
  - [Server ↔ LB](#server--lb)
  - [Client ↔ LB](#client--lb)
  - [Client ↔ Server (TCP)](#client--server-tcp)
  - [Server → Client (UDP)](#server--client-udp)
- [Scheduling Logic](#scheduling-logic)
- [Examples](#examples)
- [Troubleshooting](#troubleshooting)
- [Security Notes](#security-notes)
- [Extending](#extending)

---

## Architecture

```
+-----------+        +------------------+        +-------------------------+
|  Client   | <----> |  Load Balancer   | <----> |  Server (N instances)   |
| (TCP+UDP) |  TCP   |  (TCP admin,     |  TCP   |  TCP cmd, UDP streaming |
|           |        |  TCP reg/pings)  |        |  + periodic LB reports  |
+-----------+        +------------------+        +-------------------------+
         ^                       ^                             |
         |  UDP ticks            |                             | UDP
         +-----------------------+-----------------------------+
```

- **Client ↔ LB (TCP 11114):** client asks for a backend; LB replies with `host:port`.
- **Server ↔ LB (TCP 11115):** server registers (`!join`) and periodically reports live clients (`!report`).
- **Client ↔ Server (TCP):** line-based command protocol (`hello`, `ping`, `sendfile`, `stream`, …).
- **Server → Client (UDP):** optional periodic “tick” packets; client listens and prints.

---

## Features

- **Static RR** with **weights**, **drain**/undrain, **manual removal**.
- **Dynamic selection** via **background RTT probes** (configurable interval).
- **Live client visibility** (servers report current sessions to LB).
- **Access controls:** ban by client name or IP.
- **Max connections/server** (uses server live reports).
- **Multi-instance servers** on the same host (ephemeral ports by default).
- **Responsive streaming cancel** (background thread, immediate `cancel`).
- **Text-safe file transfer** (Base64 framed).

---

## Ports & Protocols

| Component | Purpose                       | Default Port |
|----------:|-------------------------------|:------------:|
| LB        | Client handshakes             | **11114/TCP** |
| LB        | Server register & reports     | **11115/TCP** |
| Server    | Client commands               | **ephemeral (0)** or custom (e.g., 11112/TCP) |
| Server    | UDP streaming source          | **ephemeral (0)** or custom (e.g., 11113/UDP) |
| Client    | UDP listening port            | **ephemeral (auto)** |

> Servers advertise the **actual bound TCP port** to the LB, so you can run many in parallel.

---

## Build

Requires **JDK 8+**.

```bash
javac LoadBalancer.java Server.java Client.java
```

---

## Run

### Load Balancer

```bash
java LoadBalancer
```

Interactive admin console is available in the same terminal; type `help`.

---

### Server (multi-instance)

Run one or many:

```bash
# Ephemeral TCP/UDP ports (recommended for many instances)
java Server

# Explicit ports (optional)
java Server --port 11112 --udp-port 11113 --lb-host localhost --lb-port 11115

# Short flags:
java Server -p 0 -u 0
```

Each server registers with the LB using its **real TCP port**.

---

### Client

```bash
# Static or dynamic selection; name is optional
java Client --mode dynamic --name Alice
java Client -m static -n Bob
```

Interactive commands after connected (type into client’s stdin):

```
hello Alice
udp 54321                # auto-sent by client using its UDP socket; you can resend
stream                   # start UDP ticks
cancel                   # stop streaming (immediate)
ping | time | ls | pwd   # utility commands
sendfile README.md       # Base64 framed transfer
compute 3                # sleep 3s to simulate work
quit
```

---

## Admin Console (LB)

Type these in the **LB terminal**:

- **Cluster views**
  - `servers` — list servers with RTT, weight, drain flag, live client count
  - `live` — live clients per server (reported)
  - `clients` / `recent` — LB’s recent assignment history
  - `status` — `servers` + `live`
- **Capacity / policy**
  - `set ping <ms>` — RTT probe interval (default 1000ms)
  - `set maxconn <N>` — cap live clients per server
  - `mode default <static|dynamic>` — default when client omits mode
- **Weights & draining**
  - `setweight <host:port> <N>` — weighted RR
  - `weights` — show weights
  - `drain <host:port|all>` / `undrain <host:port|all>` / `drained`
- **Access control**
  - `ban ip <x>` / `unban ip <x>`
  - `ban name <x>` / `unban name <x>`
  - `bans`
- **Maintenance**
  - `remove <host:port>` — unregister
  - `clear` — clear assignment history
  - `help`

> **Note:** “live” lists are **actual connected clients** (reported by servers), while “recent” shows whom the LB **directed** to which server.

---

## Wire Protocols

### Server ↔ LB

- **Register:**  
  `!join -v dynamic <tcpPort>` → LB replies `!ack`

- **Report (every ~2s):**  
  `!report <tcpPort> clients <N> <name>@<ip> ...`  
  LB updates its live view and uses it for `maxconn` and console.

### Client ↔ LB

- **Handshake:**  
  `HELLO <name> <mode>` where `<mode>` ∈ `{static,dynamic}`  
  (name optional; LB will assign `Client-<id>` if missing)

- **Reply:**  
  `<serverHost>:<serverPort>` or `NO_SERVER_AVAILABLE`

### Client ↔ Server (TCP)

Line-based commands:

- `hello <name>` → `Hello, <name>!`
- `udp <port>` — register client’s UDP port for streaming
- `stream` / `cancel`
- `ping` → `pong`
- `time` → server time
- `ls`, `pwd`
- `compute <seconds>`
- `sendfile [-v] <filename>` — **framed, Base64**
  - Header: `FILE <name> <sizeBytes>`
  - Content: multiple Base64 lines
  - Terminator: `ENDFILE`
- `quit`

### Server → Client (UDP)

- Payload: simple UTF-8 `"tick <millis>"` every ~2s while streaming.

---

## Scheduling Logic

1. **Candidate set** = registered servers **minus** any **drained** servers and any server at or above **maxconn** (uses live reports).
2. **Dynamic mode:** pick the **lowest RTT** among candidates (background cache).
3. **Static mode:** **weighted** round-robin across candidates using `setweight`.
4. If dynamic yields none (no RTT yet), fallback to static.

---

## Examples

**Start cluster**
```bash
# Terminal 1 (LB)
java LoadBalancer

# Terminal 2..N (multiple servers)
java Server
java Server
java Server -p 0 -u 0
```

**Connect clients**
```bash
java Client -n Alice -m dynamic
java Client -n Bob   -m static
```

**In the LB console**
```
servers
live
set maxconn 1
status
drain 127.0.0.1:12345
setweight 127.0.0.1:23456 5
ban name Alice
bans
```

---

## Troubleshooting

- **Server logs “Accepted connection …” too often**  
  That’s the LB’s RTT pinger. It connects briefly and sends `ping`; servers reply `pong` without verbose logging. This is expected.

- **`cancel` isn’t immediate**  
  Streaming runs in a **background thread** and checks a `volatile` flag; `cancel` interrupts the thread. If you customized the code and call `stream` on the **same reader thread**, move it back to a background thread.

- **No UDP ticks on client**  
  Ensure the client **sent** `udp <port>` (the provided client auto-registers its UDP port). Firewalls may need UDP allowances.

- **Multiple servers on same host**  
  Use **ephemeral** ports (`-p 0 -u 0`) or unique explicit ports. Each server registers its **real TCP port**.

- **File transfer dumps gibberish in console**  
  The protocol is **framed & Base64** to stay line-safe. The client saves received data to `received_<name>` and logs bytes.

---

## Security Notes

- **No auth**: anyone can register a server or request routing; use **bans**, **drain**, and run on a **trusted network** only.
- **File serving is local-filesystem** access on the server process; sandbox accordingly.
- **No TLS**: use stunnel/SSH tunnel or embed TLS if used beyond a lab.

---

## Extending

- **Heartbeats & eviction**: prune servers missing reports for N intervals.
- **Health checks**: track success/failure rates per server.
- **Sticky sessions**: consistent hashing by client name/IP.
- **Graceful drain**: server rejects new connections but keeps existing until idle.
- **JSON admin API**: expose read-only views and actions over HTTP.

---
