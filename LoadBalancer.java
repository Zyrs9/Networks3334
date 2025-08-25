import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadBalancer {
    private static final int CLIENT_PORT = 11114;         // client handshakes
    private static final int REGISTRATION_PORT = 11115;   // server join + reports

    private ServerSocket clientSocket;
    private ServerSocket registrationSocket;

    // Registered servers (by value object)
    private final List<ServerInfo> servers = Collections.synchronizedList(new ArrayList<>());

    // Weighted RR support
    private final ConcurrentMap<ServerInfo, Integer> weights = new ConcurrentHashMap<>();
    private volatile List<ServerInfo> weightedList = new CopyOnWriteArrayList<>();
    private final AtomicInteger rrIndex = new AtomicInteger(0);

    // Dynamic mode RTT cache
    private final ConcurrentMap<ServerInfo, Long> rtts = new ConcurrentHashMap<>();
    private ScheduledExecutorService rttScheduler;
    private volatile int pingIntervalMs = 1000;

    // Assignment bookkeeping (best-effort)
    private final ConcurrentMap<ServerInfo, CopyOnWriteArrayList<ClientRecord>> assignedByServer = new ConcurrentHashMap<>();
    private final Deque<ClientRecord> recentAssignments = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECENT = 500;

    // Live client reports from servers
    private final ConcurrentMap<ServerInfo, CopyOnWriteArrayList<LiveClient>> liveByServer = new ConcurrentHashMap<>();

    // Drain / limits / bans
    private final Set<ServerInfo> drained = ConcurrentHashMap.newKeySet();
    private volatile int maxPerServer = Integer.MAX_VALUE;        // enforce using live reports
    private final Set<String> bannedIps = ConcurrentHashMap.newKeySet();
    private final Set<String> bannedNames = ConcurrentHashMap.newKeySet();

    // Default mode when client omits it
    private volatile String defaultMode = "static";

    private final AtomicInteger clientIdCounter = new AtomicInteger(1);

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public LoadBalancer() throws IOException {
        clientSocket = new ServerSocket(CLIENT_PORT);
        registrationSocket = new ServerSocket(REGISTRATION_PORT);
        System.out.println("Load Balancer on " + CLIENT_PORT + " (clients), " + REGISTRATION_PORT + " (servers)");
        startRttRefresher();
    }

    public void start() {
        new Thread(this::handleServerChannel, "LB-ServerChannel").start();
        new Thread(this::handleClientRequests, "LB-Clients").start();
        new Thread(this::adminConsole, "LB-Console").start();
    }

    // =================== Admin Console ===================
    private void adminConsole() {
        System.out.println("Admin console ready. Type 'help'.");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 0 || parts[0].isEmpty()) continue;
                String cmd = parts[0].toLowerCase(Locale.ROOT);

                try {
                    switch (cmd) {
                        case "servers": printServers(); break;
                        case "clients": printAssigned(); break;
                        case "live": printLive(); break;
                        case "status":
                            printServers();
                            printLive();
                            break;
                        case "recent": printRecent(); break;

                        case "drained": printDrained(); break;
                        case "drain":
                            if (parts.length == 2 && "all".equalsIgnoreCase(parts[1])) drainAll();
                            else if (parts.length == 2) drain(parseServer(parts[1]));
                            else System.out.println("Usage: drain <host:port|all>");
                            break;
                        case "undrain":
                            if (parts.length == 2 && "all".equalsIgnoreCase(parts[1])) undrainAll();
                            else if (parts.length == 2) undrain(parseServer(parts[1]));
                            else System.out.println("Usage: undrain <host:port|all>");
                            break;

                        case "setweight":
                            if (parts.length != 3) { System.out.println("Usage: setweight <host:port> <N>"); break; }
                            setWeight(parseServer(parts[1]), Integer.parseInt(parts[2]));
                            break;
                        case "weights": printWeights(); break;

                        case "mode":
                            if (parts.length == 3 && "default".equalsIgnoreCase(parts[1])) {
                                if (!"static".equalsIgnoreCase(parts[2]) && !"dynamic".equalsIgnoreCase(parts[2])) {
                                    System.out.println("Value must be static|dynamic");
                                } else {
                                    defaultMode = parts[2].toLowerCase(Locale.ROOT);
                                    System.out.println("Default mode set to " + defaultMode);
                                }
                            } else {
                                System.out.println("Usage: mode default <static|dynamic>");
                            }
                            break;

                        case "set":
                            if (parts.length == 3 && "ping".equalsIgnoreCase(parts[1])) {
                                int ms = Integer.parseInt(parts[2]);
                                setPingInterval(ms);
                                System.out.println("RTT ping interval set to " + ms + "ms");
                            } else if (parts.length == 3 && "maxconn".equalsIgnoreCase(parts[1])) {
                                maxPerServer = Integer.parseInt(parts[2]);
                                System.out.println("Max live clients per server set to " + maxPerServer);
                            } else {
                                System.out.println("Usage: set ping <ms> | set maxconn <N>");
                            }
                            break;

                        case "ban":
                            if (parts.length == 3 && "ip".equalsIgnoreCase(parts[1])) {
                                bannedIps.add(parts[2]); System.out.println("Banned IP " + parts[2]);
                            } else if (parts.length == 3 && "name".equalsIgnoreCase(parts[1])) {
                                bannedNames.add(parts[2]); System.out.println("Banned name " + parts[2]);
                            } else System.out.println("Usage: ban ip <x> | ban name <x>");
                            break;
                        case "unban":
                            if (parts.length == 3 && "ip".equalsIgnoreCase(parts[1])) {
                                bannedIps.remove(parts[2]); System.out.println("Unbanned IP " + parts[2]);
                            } else if (parts.length == 3 && "name".equalsIgnoreCase(parts[1])) {
                                bannedNames.remove(parts[2]); System.out.println("Unbanned name " + parts[2]);
                            } else System.out.println("Usage: unban ip <x> | unban name <x>");
                            break;
                        case "bans":
                            System.out.println("Banned IPs: " + bannedIps);
                            System.out.println("Banned names: " + bannedNames);
                            break;

                        case "remove":
                            if (parts.length != 2) { System.out.println("Usage: remove <host:port>"); break; }
                            removeServer(parseServer(parts[1]));
                            break;

                        case "clear":
                            assignedByServer.clear();
                            recentAssignments.clear();
                            System.out.println("Cleared assignment history.");
                            break;

                        case "help":
                            System.out.println("Commands:\n" +
                                    "  servers          - list servers (rtt, weight, drain, live)\n" +
                                    "  live             - show live clients per server (reported)\n" +
                                    "  clients          - show recent LB assignments\n" +
                                    "  status           - servers + live\n" +
                                    "  recent           - last " + MAX_RECENT + " assignments\n" +
                                    "  drain <hp|all>   - mark server(s) unschedulable\n" +
                                    "  undrain <hp|all> - make schedulable again\n" +
                                    "  drained          - list drained servers\n" +
                                    "  setweight <hp> <N> | weights\n" +
                                    "  mode default <static|dynamic>\n" +
                                    "  set ping <ms> | set maxconn <N>\n" +
                                    "  ban ip <x> | ban name <x> | unban ip/name <x> | bans\n" +
                                    "  remove <host:port>\n" +
                                    "  clear | help");
                            break;
                        default:
                            System.out.println("Unknown command. Type 'help'.");
                    }
                } catch (Exception ex) {
                    System.out.println("Error: " + ex.getMessage());
                }
            }
        } catch (IOException e) {
            System.out.println("Console error: " + e.getMessage());
        }
    }

    private void printServers() {
        System.out.println("\n== SERVERS ==");
        List<ServerInfo> snapshot;
        synchronized (servers) { snapshot = new ArrayList<>(servers); }
        if (snapshot.isEmpty()) { System.out.println("(none)"); return; }
        snapshot.sort(Comparator.comparing((ServerInfo s) -> s.address).thenComparingInt(s -> s.port));
        for (ServerInfo s : snapshot) {
            Long rtt = rtts.get(s);
            int live = liveByServer.getOrDefault(s, new CopyOnWriteArrayList<>()).size();
            Integer w = weights.getOrDefault(s, 1);
            String flags = (drained.contains(s) ? " drained" : "");
            System.out.printf("- %s:%d  rtt=%s  weight=%d  live=%d%s%n",
                    s.address, s.port, (rtt == null ? "n/a" : (rtt + "ms")), w, live, flags);
        }
    }

    private void printAssigned() {
        System.out.println("\n== RECENT ASSIGNMENTS (by LB) ==");
        if (recentAssignments.isEmpty()) { System.out.println("(none)"); return; }
        recentAssignments.stream().limit(100)
                .forEach(cr -> System.out.printf("%s  client=%s  mode=%s  -> %s:%d  from=%s%n",
                        TS_FMT.format(Instant.ofEpochMilli(cr.assignedAt)), cr.clientName, cr.mode, cr.server.address, cr.server.port, cr.lbSeenRemote));
    }

    private void printRecent() { printAssigned(); }

    private void printLive() {
        System.out.println("\n== LIVE CLIENTS (reported by servers) ==");
        List<ServerInfo> snapshot;
        synchronized (servers) { snapshot = new ArrayList<>(servers); }
        if (snapshot.isEmpty()) { System.out.println("(no servers)"); return; }
        snapshot.sort(Comparator.comparing((ServerInfo s) -> s.address).thenComparingInt(s -> s.port));
        for (ServerInfo s : snapshot) {
            List<LiveClient> list = liveByServer.getOrDefault(s, new CopyOnWriteArrayList<>());
            System.out.printf("%s:%d  (%d clients)%n", s.address, s.port, list.size());
            for (LiveClient lc : list) {
                System.out.printf("  • %s  ip=%s  at=%s%n", lc.name, lc.ip, TS_FMT.format(Instant.ofEpochMilli(lc.reportedAt)));
            }
        }
        System.out.println();
    }

    private void printWeights() {
        System.out.println("\n== WEIGHTS ==");
        if (weights.isEmpty()) { System.out.println("(all weight=1)"); return; }
        weights.forEach((s,w) -> System.out.println(s + " -> " + w));
    }

    private void printDrained() {
        System.out.println("\n== DRAINED SERVERS ==");
        if (drained.isEmpty()) { System.out.println("(none)"); return; }
        drained.forEach(s -> System.out.println("- " + s));
    }

    // =================== Server channel ===================
    private void handleServerChannel() {
        while (true) {
            try (Socket s = registrationSocket.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                 PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {

                String msg = in.readLine();
                if (msg == null) continue;

                if (msg.startsWith("!join")) {
                    // "!join ... <port>"
                    String[] parts = msg.trim().split("\\s+");
                    int port = Integer.parseInt(parts[parts.length - 1]);
                    ServerInfo info = new ServerInfo(s.getInetAddress().getHostAddress(), port);
                    synchronized (servers) {
                        if (!servers.contains(info)) {
                            servers.add(info);
                            weights.putIfAbsent(info, 1);
                            rebuildWeighted();
                            System.out.println("Registered server: " + info);
                        } else {
                            System.out.println("Server already registered: " + info);
                        }
                    }
                    out.println("!ack");
                } else if (msg.startsWith("!report")) {
                    // "!report <port> clients <n> <name>@<ip> ..."
                    try { handleReport(s.getInetAddress().getHostAddress(), msg); }
                    catch (Exception ex) { System.out.println("Bad report: " + ex.getMessage()); }
                    // no response needed
                } else {
                    out.println("!err");
                }
            } catch (Exception e) {
                System.out.println("Registration channel error: " + e.getMessage());
            }
        }
    }

    private void handleReport(String srcIp, String line) {
        String[] p = line.trim().split("\\s+");
        if (p.length < 4 || !p[2].equalsIgnoreCase("clients")) return;
        int tcpPort = Integer.parseInt(p[1]);
        int n = Integer.parseInt(p[3]);

        ServerInfo key = new ServerInfo(srcIp, tcpPort);
        List<LiveClient> list = new ArrayList<>();
        for (int i = 0; i < n && 4 + 1 + i <= p.length - 1; i++) {
            String token = p[4 + i]; // "<name>@<ip>"
            int at = token.lastIndexOf('@');
            String name = (at > 0) ? token.substring(0, at) : token;
            String ip = (at > 0) ? token.substring(at + 1) : "unknown";
            list.add(new LiveClient(name, ip, System.currentTimeMillis()));
        }
        liveByServer.put(key, new CopyOnWriteArrayList<>(list));
    }

    // =================== Client handling ===================
    private void handleClientRequests() {
        while (true) {
            try (Socket client = clientSocket.accept()) {
                String lbSeenRemote = String.valueOf(client.getRemoteSocketAddress());
                String remoteIp = extractIp(lbSeenRemote);

                client.setSoTimeout(1000);
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                PrintWriter out = new PrintWriter(client.getOutputStream(), true);

                String name = null;
                String mode = defaultMode;
                String line = null;
                try { line = in.readLine(); } catch (IOException ignore) {}
                if (line != null && line.toUpperCase().startsWith("HELLO")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) name = parts[1];
                    if (parts.length >= 3) mode = parts[2].toLowerCase(Locale.ROOT);
                }
                if (name == null || name.isEmpty()) {
                    name = "Client-" + clientIdCounter.getAndIncrement();
                }

                // Bans
                if (bannedIps.contains(remoteIp) || bannedNames.contains(name)) {
                    out.println("NO_SERVER_AVAILABLE");
                    System.out.println("Denied client '" + name + "' from " + remoteIp + " (banned).");
                    continue;
                }

                ServerInfo chosen = "dynamic".equalsIgnoreCase(mode) ? selectServerDynamic() : selectServerStatic();
                if (chosen == null) {
                    out.println("NO_SERVER_AVAILABLE");
                    System.out.println("No server available for client '" + name + "'");
                    continue;
                }
                out.println(chosen.address + ":" + chosen.port);
                System.out.println("Client '" + name + "' (" + mode + ") -> " + chosen);

                ClientRecord rec = new ClientRecord(name, mode, System.currentTimeMillis(), chosen, lbSeenRemote);
                assignedByServer.computeIfAbsent(chosen, k -> new CopyOnWriteArrayList<>()).add(rec);
                recentAssignments.addLast(rec);
                while (recentAssignments.size() > MAX_RECENT) recentAssignments.pollFirst();

            } catch (Exception ex) {
                System.out.println("Error processing client: " + ex.getMessage());
            }
        }
    }

    private String extractIp(String remote) {
        // formats like "/127.0.0.1:54321"
        int slash = remote.indexOf('/');
        int colon = remote.lastIndexOf(':');
        if (slash >= 0 && colon > slash) return remote.substring(slash + 1, colon);
        colon = remote.indexOf(':');
        if (colon > 0) return remote.substring(0, colon);
        return remote;
    }

    // =================== Selection ===================
    private ServerInfo selectServerStatic() {
        List<ServerInfo> candidates = getSchedulableServers();
        if (candidates.isEmpty()) return null;
        // if we have weights, run RR over 'weightedList' but filter at selection time
        for (int tries = 0; tries < weightedList.size() * 2; tries++) {
            int idx = Math.floorMod(rrIndex.getAndIncrement(), Math.max(weightedList.size(), 1));
            ServerInfo s = weightedList.isEmpty() ? candidates.get(idx % candidates.size()) : weightedList.get(idx);
            if (isSchedulableNow(s)) return s;
        }
        // fallback: first schedulable
        for (ServerInfo s : candidates) if (isSchedulableNow(s)) return s;
        return null;
    }

    private ServerInfo selectServerDynamic() {
        List<ServerInfo> candidates = getSchedulableServers();
        if (candidates.isEmpty()) return null;
        ServerInfo best = null; long bestT = Long.MAX_VALUE;
        for (ServerInfo s : candidates) {
            if (!isSchedulableNow(s)) continue;
            Long t = rtts.get(s);
            if (t != null && t < bestT) { bestT = t; best = s; }
        }
        if (best != null) return best;
        return selectServerStatic();
    }

    private List<ServerInfo> getSchedulableServers() {
        List<ServerInfo> snap;
        synchronized (servers) { snap = new ArrayList<>(servers); }
        snap.removeIf(drained::contains);
        return snap;
    }

    private boolean isSchedulableNow(ServerInfo s) {
        if (drained.contains(s)) return false;
        if (maxPerServer == Integer.MAX_VALUE) return true;
        int live = liveByServer.getOrDefault(s, new CopyOnWriteArrayList<>()).size();
        return live < maxPerServer;
    }

    // =================== RTT refresher ===================
    private void startRttRefresher() {
        rttScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LB-RTT"); t.setDaemon(true); return t;
        });
        rttScheduler.scheduleAtFixedRate(() -> {
            List<ServerInfo> snap;
            synchronized (servers) { snap = new ArrayList<>(servers); }
            snap.parallelStream().forEach(s -> {
                long t = pingServer(s, Math.max(200, pingIntervalMs / 2));
                if (t >= 0) rtts.put(s, t);
            });
        }, 0, pingIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void setPingInterval(int ms) {
        pingIntervalMs = Math.max(200, ms);
        // restart scheduler
        rttScheduler.shutdownNow();
        startRttRefresher();
    }

    private long pingServer(ServerInfo s, int timeoutMs) {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(s.address, s.port), timeoutMs);
            sock.setSoTimeout(timeoutMs);
            PrintWriter out = new PrintWriter(sock.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            long start = System.nanoTime();
            out.println("ping");
            String resp = in.readLine();
            long end = System.nanoTime();
            if (resp != null && resp.trim().equalsIgnoreCase("pong")) {
                return (end - start) / 1_000_000; // ms
            }
        } catch (IOException ignored) {}
        return -1;
    }

    // =================== Helpers ===================
    private void rebuildWeighted() {
        List<ServerInfo> base;
        synchronized (servers) { base = new ArrayList<>(servers); }
        List<ServerInfo> w = new ArrayList<>();
        for (ServerInfo s : base) {
            int times = Math.max(1, weights.getOrDefault(s, 1));
            for (int i = 0; i < times; i++) w.add(s);
        }
        weightedList = new CopyOnWriteArrayList<>(w);
    }

    private ServerInfo parseServer(String hp) {
        String[] a = hp.split(":");
        if (a.length != 2) throw new IllegalArgumentException("host:port required");
        return new ServerInfo(a[0], Integer.parseInt(a[1]));
    }

    private void setWeight(ServerInfo s, int w) {
        if (w < 1) w = 1;
        if (!servers.contains(s)) { System.out.println("No such server registered: " + s); return; }
        weights.put(s, w);
        rebuildWeighted();
        System.out.println("Weight set: " + s + " -> " + w);
    }

    private void removeServer(ServerInfo s) {
        synchronized (servers) { servers.remove(s); }
        weights.remove(s);
        drained.remove(s);
        liveByServer.remove(s);
        assignedByServer.remove(s);
        rebuildWeighted();
        System.out.println("Removed server: " + s);
    }

    private void drain(ServerInfo s) {
        if (!servers.contains(s)) { System.out.println("No such server: " + s); return; }
        drained.add(s);
        System.out.println("Drained: " + s);
    }

    private void undrain(ServerInfo s) {
        drained.remove(s);
        System.out.println("Undrained: " + s);
    }

    private void drainAll() {
        synchronized (servers) { drained.addAll(servers); }
        System.out.println("All servers drained.");
    }

    private void undrainAll() {
        drained.clear();
        System.out.println("All servers undrained.");
    }

    public static void main(String[] args) {
        try {
            LoadBalancer lb = new LoadBalancer();
            lb.start();
        } catch (IOException e) {
            System.out.println("Failed to start LoadBalancer: " + e.getMessage());
        }
    }

    // =================== Types ===================
    static class ServerInfo {
        final String address; final int port;
        ServerInfo(String addr, int port) { this.address = addr; this.port = port; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ServerInfo)) return false;
            ServerInfo that = (ServerInfo) o;
            return port == that.port && Objects.equals(address, that.address);
        }
        @Override public int hashCode() { return Objects.hash(address, port); }
        @Override public String toString() { return address + ":" + port; }
    }

    static class ClientRecord {
        final String clientName, mode, lbSeenRemote;
        final long assignedAt;
        final ServerInfo server;
        ClientRecord(String clientName, String mode, long assignedAt, ServerInfo server, String lbSeenRemote) {
            this.clientName = clientName; this.mode = mode; this.assignedAt = assignedAt; this.server = server; this.lbSeenRemote = lbSeenRemote;
        }
    }

    static class LiveClient {
        final String name, ip; final long reportedAt;
        LiveClient(String name, String ip, long ts) { this.name = name; this.ip = ip; this.reportedAt = ts; }
    }
}
