import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadBalancer {
    private static final int CLIENT_PORT = 11114;         // Port to handle client requests
    private static final int REGISTRATION_PORT = 11115;   // Port to accept server registrations

    private ServerSocket clientSocket;
    private ServerSocket registrationSocket;

    private final List<ServerInfo> servers = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);
    private final AtomicInteger clientIdCounter = new AtomicInteger(1);

    // Dynamic mode support: cache RTTs from background pings
    private final ConcurrentMap<ServerInfo, Long> rtts = new ConcurrentHashMap<>();
    private ScheduledExecutorService rttScheduler;

    public LoadBalancer() throws IOException {
        clientSocket = new ServerSocket(CLIENT_PORT);
        registrationSocket = new ServerSocket(REGISTRATION_PORT);
        System.out.println("Load Balancer running on ports " + CLIENT_PORT + " (clients) and " + REGISTRATION_PORT + " (registrations)");
        startRttRefresher();
    }

    public void start() {
        new Thread(this::handleServerRegistrations, "LB-Registration").start();
        new Thread(this::handleClientRequests, "LB-Clients").start();
    }

    private void handleServerRegistrations() {
        while (true) {
            try (Socket s = registrationSocket.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                 PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {

                String msg = in.readLine();
                if (msg != null && msg.startsWith("!join")) {
                    String[] parts = msg.trim().split("\\s+");
                    // last token is the port (supports "!join -v dynamic 11112" or similar)
                    int port = Integer.parseInt(parts[parts.length - 1]);
                    ServerInfo info = new ServerInfo(s.getInetAddress().getHostAddress(), port);
                    synchronized (servers) {
                        if (!servers.contains(info)) {
                            servers.add(info);
                            System.out.println("Registered server: " + info);
                        } else {
                            System.out.println("Server already registered: " + info);
                        }
                    }
                    out.println("!ack");
                } else {
                    out.println("!err");
                }
            } catch (Exception e) {
                System.out.println("Registration handling error: " + e.getMessage());
            }
        }
    }

    private void handleClientRequests() {
        while (true) {
            try (Socket client = clientSocket.accept()) {
                String clientName = null;
                String mode = "static"; // default

                client.setSoTimeout(1000);
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                PrintWriter out = new PrintWriter(client.getOutputStream(), true);

                String line = null;
                try {
                    line = in.readLine();
                } catch (IOException ignore) { /* timeout or no hello */ }

                if (line != null && line.toUpperCase().startsWith("HELLO")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) clientName = parts[1];
                    if (parts.length >= 3) mode = parts[2].toLowerCase(Locale.ROOT);
                }
                if (clientName == null || clientName.isEmpty()) {
                    clientName = "Client-" + clientIdCounter.getAndIncrement();
                }

                ServerInfo server = "dynamic".equalsIgnoreCase(mode) ? selectServerDynamic() : selectServerStatic();
                if (server != null) {
                    out.println(server.address + ":" + server.port);
                    System.out.println("Client '" + clientName + "' (" + mode + ") -> " + server);
                } else {
                    out.println("NO_SERVER_AVAILABLE");
                    System.out.println("No server available for client '" + clientName + "'");
                }
            } catch (Exception ex) {
                System.out.println("Error processing client: " + ex.getMessage());
            }
        }
    }

    private ServerInfo selectServerStatic() {
        synchronized (servers) {
            int n = servers.size();
            if (n == 0) {
                System.out.println("No servers are currently registered.");
                return null;
            }
            int idx = Math.floorMod(roundRobinIndex.getAndIncrement(), n);
            return servers.get(idx);
        }
    }

    private ServerInfo selectServerDynamic() {
        ServerInfo best = null;
        long bestT = Long.MAX_VALUE;
        synchronized (servers) {
            if (servers.isEmpty()) {
                System.out.println("No servers are currently registered.");
                return null;
            }
            for (ServerInfo s : servers) {
                Long t = rtts.get(s);
                if (t != null && t < bestT) {
                    bestT = t;
                    best = s;
                }
            }
        }
        return best != null ? best : selectServerStatic();
    }

    private void startRttRefresher() {
        rttScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LB-RTT-Refresher");
            t.setDaemon(true);
            return t;
        });
        rttScheduler.scheduleAtFixedRate(() -> {
            List<ServerInfo> snapshot;
            synchronized (servers) {
                snapshot = new ArrayList<>(servers);
            }
            snapshot.parallelStream().forEach(s -> {
                long t = pingServer(s, 300);
                if (t >= 0) {
                    rtts.put(s, t);
                }
            });
        }, 0, 1, TimeUnit.SECONDS);
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

    public static void main(String[] args) {
        try {
            LoadBalancer lb = new LoadBalancer();
            lb.start();
        } catch (IOException e) {
            System.out.println("Failed to start LoadBalancer: " + e.getMessage());
        }
    }

    static class ServerInfo {
        final String address;
        final int port;

        ServerInfo(String addr, int port) {
            this.address = addr;
            this.port = port;
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ServerInfo)) return false;
            ServerInfo that = (ServerInfo) o;
            return port == that.port && Objects.equals(address, that.address);
        }
        @Override public int hashCode() { return Objects.hash(address, port); }
        @Override public String toString() { return address + ":" + port; }
    }
}
