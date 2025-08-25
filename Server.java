import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Server {
    private static final String DEFAULT_LB_HOST = "localhost";
    private static final int DEFAULT_LB_REG_PORT = 11115;

    private static DatagramSocket udpSocket;

    public static void main(String[] args) throws IOException {
        int tcpPort = 0;   // 0 => ephemeral
        int udpPort = 0;   // 0 => ephemeral
        String lbHost = DEFAULT_LB_HOST;
        int lbRegPort = DEFAULT_LB_REG_PORT;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (("--port".equalsIgnoreCase(a) || "-p".equalsIgnoreCase(a)) && i + 1 < args.length) {
                tcpPort = Integer.parseInt(args[++i]);
            } else if (("--udp-port".equalsIgnoreCase(a) || "-u".equalsIgnoreCase(a)) && i + 1 < args.length) {
                udpPort = Integer.parseInt(args[++i]);
            } else if ("--lb-host".equalsIgnoreCase(a) && i + 1 < args.length) {
                lbHost = args[++i];
            } else if (("--lb-port".equalsIgnoreCase(a) || "--lb-reg-port".equalsIgnoreCase(a)) && i + 1 < args.length) {
                lbRegPort = Integer.parseInt(args[++i]);
            }
        }

        ServerSocket serverSocket = new ServerSocket(tcpPort);
        serverSocket.setReuseAddress(true);
        tcpPort = serverSocket.getLocalPort();

        udpSocket = new DatagramSocket(udpPort);
        udpSocket.setReuseAddress(true);
        udpPort = udpSocket.getLocalPort();

        registerWithLoadBalancer(lbHost, lbRegPort, tcpPort);
        System.out.println("Server listening on TCP " + tcpPort + " and UDP " + udpPort
                + " (LB " + lbHost + ":" + lbRegPort + ")");

        // start reporter thread (every ~2s)
        ServerReporter reporter = new ServerReporter(lbHost, lbRegPort, tcpPort);
        new Thread(reporter, "Srv-Reporter-" + tcpPort).start();

        while (true) {
            try {
                Socket clientSocket = serverSocket.accept();
                new Thread(new ClientHandler(clientSocket, reporter), "Srv-Client-" + clientSocket.getPort()).start();
            } catch (IOException e) {
                System.out.println("Accept error: " + e.getMessage());
            }
        }
    }

    private static void registerWithLoadBalancer(String lbHost, int lbRegPort, int tcpPort) {
        try (Socket socket = new Socket(lbHost, lbRegPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.println("!join -v dynamic " + tcpPort);
            String response = in.readLine();
            if ("!ack".equals(response)) {
                System.out.println("Registered with Load Balancer on TCP port " + tcpPort);
            } else {
                System.out.println("Load Balancer did not ack registration (response=" + response + ")");
            }
        } catch (IOException e) {
            System.out.println("Could not connect to Load Balancer: " + e.getMessage());
        }
    }

    // ======= Reporter (keeps a live set of clients and periodically reports to LB) =======
    static class ServerReporter implements Runnable {
        private final String lbHost; private final int lbPort; private final int tcpPort;
        private final Set<ClientKey> live = ConcurrentHashMap.newKeySet();

        ServerReporter(String lbHost, int lbPort, int tcpPort) {
            this.lbHost = lbHost; this.lbPort = lbPort; this.tcpPort = tcpPort;
        }

        void onConnect(Socket s) { live.add(ClientKey.fromSocket(s)); }
        void onHello(Socket s, String name) {
            ClientKey k = ClientKey.fromSocket(s);
            live.remove(k);
            live.add(new ClientKey(name, k.ip, k.port));
        }
        void onDisconnect(Socket s) { live.remove(ClientKey.fromSocket(s)); }

        @Override public void run() {
            try {
                while (true) {
                    // Build "!report <tcpPort> clients <n> <name>@<ip> ..."
                    StringBuilder sb = new StringBuilder();
                    sb.append("!report ").append(tcpPort).append(" clients ").append(live.size());
                    for (ClientKey k : live) {
                        String name = (k.name == null || k.name.isEmpty()) ? "unknown" : k.name;
                        sb.append(' ').append(name).append('@').append(k.ip);
                    }
                    try (Socket s = new Socket(lbHost, lbPort);
                         PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
                        out.println(sb.toString());
                    } catch (IOException ignored) {}
                    Thread.sleep(2000);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        static class ClientKey {
            final String name; final String ip; final int port; // remote port (for uniqueness)
            ClientKey(String name, String ip, int port) { this.name = name; this.ip = ip; this.port = port; }

            static ClientKey fromSocket(Socket s) {
                String remote = String.valueOf(s.getRemoteSocketAddress()); // "/ip:port"
                String ip; int p;
                int slash = remote.indexOf('/');
                int colon = remote.lastIndexOf(':');
                if (slash >= 0 && colon > slash) {
                    ip = remote.substring(slash + 1, colon);
                    p = Integer.parseInt(remote.substring(colon + 1));
                } else {
                    ip = remote; p = -1;
                }
                return new ClientKey("", ip, p);
            }

            @Override public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof ClientKey)) return false;
                ClientKey that = (ClientKey) o;
                return port == that.port && Objects.equals(ip, that.ip);
            }
            @Override public int hashCode() { return Objects.hash(ip, port); }
        }
    }

    // ======= Client handler (quiet on pings, background streaming) =======
    private static class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private final ServerReporter reporter;

        private String clientName = "";
        private InetAddress clientUdpAddr = null;
        private int clientUdpPort = -1;

        private volatile boolean streaming = false;
        private Thread streamThread = null;

        ClientHandler(Socket socket, ServerReporter reporter) {
            this.clientSocket = socket; this.reporter = reporter;
            reporter.onConnect(socket);
        }

        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true)) {

                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    String[] commands = inputLine.trim().split("\\s+");
                    if (commands.length == 0) continue;
                    String cmd = commands[0].toLowerCase();

                    switch (cmd) {
                        case "hello":
                            if (commands.length > 1) {
                                clientName = commands[1];
                                out.println("Hello, " + clientName + "!");
                                reporter.onHello(clientSocket, clientName);
                            } else {
                                out.println("Hello received without name. Use: hello <name>");
                            }
                            break;

                        case "udp":
                            if (commands.length > 1) {
                                try {
                                    clientUdpPort = Integer.parseInt(commands[1]);
                                    clientUdpAddr = clientSocket.getInetAddress();
                                    out.println("UDP registered: " + clientUdpAddr.getHostAddress() + ":" + clientUdpPort);
                                } catch (NumberFormatException e) {
                                    out.println("Bad UDP port");
                                }
                            } else {
                                out.println("Usage: udp <port>");
                            }
                            break;

                        case "stream":
                            if (clientUdpAddr == null || clientUdpPort <= 0) {
                                out.println("No UDP registration. Use: udp <port> first.");
                                break;
                            }
                            if (streaming) { out.println("Already streaming."); break; }
                            streaming = true;
                            out.println("Streaming started to " + clientUdpAddr.getHostAddress() + ":" + clientUdpPort);
                            startStreamingInBackground(out);
                            break;

                        case "cancel":
                            if (streaming) {
                                streaming = false;
                                if (streamThread != null) streamThread.interrupt();
                                out.println("Streaming stop requested.");
                            } else {
                                out.println("Not streaming.");
                            }
                            break;

                        case "ping":
                            out.println("pong"); // LB RTT check
                            break;

                        case "time":
                            out.println(new Date());
                            break;

                        case "ls":
                            out.println(ls());
                            break;

                        case "pwd":
                            out.println(pwd());
                            break;

                        case "sendfile":
                            handleSendFile(commands, out);
                            break;

                        case "compute":
                            handleCompute(commands, out);
                            break;

                        case "quit":
                            out.println("Bye.");
                            clientSocket.close();
                            return;

                        default:
                            out.println("Unknown command");
                            break;
                    }
                }
            } catch (IOException e) {
                // client dropped
            } finally {
                try { clientSocket.close(); } catch (IOException ignore) {}
                streaming = false;
                if (streamThread != null) streamThread.interrupt();
                reporter.onDisconnect(clientSocket);
            }
        }

        private void startStreamingInBackground(PrintWriter out) {
            streamThread = new Thread(() -> {
                try {
                    int i = 0;
                    while (streaming && i++ < 30) {
                        byte[] buffer = ("tick " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, clientUdpAddr, clientUdpPort);
                        synchronized (udpSocket) { udpSocket.send(packet); }
                        Thread.sleep(2000);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    System.out.println("Streaming error: " + e.getMessage());
                } finally {
                    streaming = false;
                    try { out.println("Streaming ended."); } catch (Exception ignore) {}
                }
            }, "Stream-" + (clientName.isEmpty() ? String.valueOf(clientSocket.getPort()) : clientName));
            streamThread.setDaemon(true);
            streamThread.start();
        }

        private static String ls() {
            File dir = new File(".");
            StringBuilder sb = new StringBuilder();
            File[] filesList = dir.listFiles();
            if (filesList != null) {
                for (File file : filesList) sb.append(file.getName()).append(" ");
            }
            return sb.toString().trim();
        }

        private static String pwd() { return System.getProperty("user.dir"); }

        // Base64 line protocol to keep it text-safe
        private void handleSendFile(String[] commands, PrintWriter out) {
            boolean verbose = commands.length > 2 && "-v".equalsIgnoreCase(commands[1]);
            String fileName = verbose ? commands[2] : (commands.length > 1 ? commands[1] : null);

            if (fileName == null) { out.println("Usage: sendfile [-v] <filename>"); return; }

            File file = new File(fileName);
            if (!file.exists() || !file.isFile()) { out.println("ERROR File not found"); return; }

            out.println("FILE " + file.getName() + " " + file.length());
            Base64.Encoder encoder = Base64.getEncoder();

            try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = fis.read(buf)) >= 0) {
                    if (n == 0) continue;
                    String b64 = encoder.encodeToString(n == buf.length ? buf : java.util.Arrays.copyOf(buf, n));
                    out.println(b64);
                    if (verbose) { System.out.println("Sent chunk (" + n + " bytes) of " + file.getName()); }
                }
            } catch (IOException e) { out.println("ERROR Sending file: " + e.getMessage()); return; }

            out.println("ENDFILE");
        }

        private static void handleCompute(String[] commands, PrintWriter out) {
            if (commands.length > 1) {
                try {
                    int seconds = Integer.parseInt(commands[1]);
                    compute(seconds);
                    out.println("Computed for " + seconds + " seconds");
                } catch (NumberFormatException e) { out.println("Bad duration"); }
            } else { out.println("Duration not specified"); }
        }

        private static void compute(int seconds) {
            try { Thread.sleep(seconds * 1000L); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
