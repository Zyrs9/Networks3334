import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

public class Server {
    // Defaults (can be overridden with CLI args)
    private static final String DEFAULT_LB_HOST = "localhost";
    private static final int DEFAULT_LB_REG_PORT = 11115;

    private static DatagramSocket udpSocket; // source socket for UDP streaming (server -> client)

    public static void main(String[] args) throws IOException {
        // ---- Parse CLI args ----
        int tcpPort = 0;       // 0 => ephemeral, allows multiple instances
        int udpPort = 0;       // 0 => ephemeral, allows multiple instances
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

        // ---- Bind sockets (TCP first so we know the actual port to register) ----
        ServerSocket serverSocket = new ServerSocket(tcpPort);
        serverSocket.setReuseAddress(true);
        tcpPort = serverSocket.getLocalPort(); // get the real port if ephemeral

        udpSocket = new DatagramSocket(udpPort);
        udpSocket.setReuseAddress(true);
        udpPort = udpSocket.getLocalPort();

        // ---- Register with LB using the real TCP port ----
        registerWithLoadBalancer(lbHost, lbRegPort, tcpPort);

        System.out.println("Server listening on TCP " + tcpPort + " and UDP " + udpPort
                + " (LB " + lbHost + ":" + lbRegPort + ")");

        // ---- Accept loop ----
        while (true) {
            try {
                Socket clientSocket = serverSocket.accept();
                // No noisy "Accepted ..." log; we'll log after a real hello
                new Thread(new ClientHandler(clientSocket), "Srv-Client-" + clientSocket.getPort()).start();
            } catch (IOException e) {
                System.out.println("Accept error: " + e.getMessage());
            }
        }
    }

    private static void registerWithLoadBalancer(String lbHost, int lbRegPort, int tcpPort) {
        try (Socket socket = new Socket(lbHost, lbRegPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            // flexible format; LB parses the last token as the port
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

    private static class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private String clientName = "";
        private InetAddress clientUdpAddr = null;
        private int clientUdpPort = -1;

        private volatile boolean streaming = false;
        private Thread streamThread = null;

        ClientHandler(Socket socket) { this.clientSocket = socket; }

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
                                System.out.println("Client identified as: " + clientName + " from " + clientSocket.getRemoteSocketAddress());
                                out.println("Hello, " + clientName + "!");
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
                            if (streaming) {
                                out.println("Already streaming.");
                                break;
                            }
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
                            out.println("pong"); // LB RTT checks hit this; keep quiet in logs
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
                System.out.println("Client handler IO error: " + e.getMessage());
            } finally {
                try { clientSocket.close(); } catch (IOException ignore) {}
                streaming = false;
                if (streamThread != null) streamThread.interrupt();
            }
        }

        private void startStreamingInBackground(PrintWriter out) {
            streamThread = new Thread(() -> {
                try {
                    int i = 0;
                    while (streaming && i++ < 30) {
                        byte[] buffer = ("tick " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, clientUdpAddr, clientUdpPort);
                        // synchronize to avoid concurrent sends on a shared DatagramSocket
                        synchronized (udpSocket) {
                            udpSocket.send(packet);
                        }
                        Thread.sleep(2000);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // normal on cancel
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
                for (File file : filesList) {
                    sb.append(file.getName()).append(" ");
                }
            }
            return sb.toString().trim();
        }

        private static String pwd() {
            return System.getProperty("user.dir");
        }

        // Line-oriented Base64 transfer to keep stream text-friendly
        private void handleSendFile(String[] commands, PrintWriter out) {
            boolean verbose = commands.length > 2 && "-v".equalsIgnoreCase(commands[1]);
            String fileName = verbose ? commands[2] : (commands.length > 1 ? commands[1] : null);

            if (fileName == null) {
                out.println("Usage: sendfile [-v] <filename>");
                return;
            }

            File file = new File(fileName);
            if (!file.exists() || !file.isFile()) {
                out.println("ERROR File not found");
                return;
            }

            out.println("FILE " + file.getName() + " " + file.length());
            Base64.Encoder encoder = Base64.getEncoder();

            try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = fis.read(buf)) >= 0) {
                    if (n == 0) continue;
                    String b64 = encoder.encodeToString(n == buf.length ? buf : java.util.Arrays.copyOf(buf, n));
                    out.println(b64);
                    if (verbose) {
                        System.out.println("Sent chunk (" + n + " bytes) of " + file.getName());
                    }
                }
            } catch (IOException e) {
                out.println("ERROR Sending file: " + e.getMessage());
                return;
            }

            out.println("ENDFILE");
        }

        private static void handleCompute(String[] commands, PrintWriter out) {
            if (commands.length > 1) {
                try {
                    int seconds = Integer.parseInt(commands[1]);
                    compute(seconds);
                    out.println("Computed for " + seconds + " seconds");
                } catch (NumberFormatException e) {
                    out.println("Bad duration");
                }
            } else {
                out.println("Duration not specified");
            }
        }

        private static void compute(int seconds) {
            try {
                System.out.println("Computing for " + seconds + " seconds...");
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Compute interrupted: " + e.getMessage());
            }
        }
    }
}
