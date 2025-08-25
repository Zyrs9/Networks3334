import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public class Client {
    private static final String LOAD_BALANCER_ADDRESS = "localhost";
    private static final int LOAD_BALANCER_PORT = 11114;

    private static DatagramSocket udpSocket;
    private static volatile boolean keepListening = true;

    public static void main(String[] args) {
        Thread udpListenerThread = null;
        try {
            // UDP socket for streaming (ephemeral local port)
            udpSocket = new DatagramSocket();
            udpListenerThread = new Thread(Client::listenForUDP, "Client-UDP");
            udpListenerThread.start();

            // Parse args
            String clientName = null;
            String mode = "static";
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (("--name".equalsIgnoreCase(a) || "-n".equalsIgnoreCase(a)) && i + 1 < args.length) {
                    clientName = args[++i];
                } else if (("--mode".equalsIgnoreCase(a) || "-m".equalsIgnoreCase(a)) && i + 1 < args.length) {
                    mode = args[++i].toLowerCase();
                }
            }
            if (clientName == null || clientName.trim().isEmpty()) {
                clientName = "Client-" + UUID.randomUUID().toString().substring(0, 8);
            }
            if (!"dynamic".equalsIgnoreCase(mode)) {
                mode = "static";
            }
            System.out.println("Client name: " + clientName + ", mode: " + mode);

            // Ask the load balancer for a server
            String serverAddress;
            try (Socket lbSocket = new Socket(LOAD_BALANCER_ADDRESS, LOAD_BALANCER_PORT);
                 BufferedReader lbIn = new BufferedReader(new InputStreamReader(lbSocket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter lbOut = new PrintWriter(new OutputStreamWriter(lbSocket.getOutputStream(), StandardCharsets.UTF_8), true)) {

                lbOut.println("HELLO " + clientName + " " + mode);
                serverAddress = lbIn.readLine();
            }

            if (serverAddress == null) throw new IOException("No response from load balancer");
            serverAddress = serverAddress.replace("/", "").trim();
            if ("NO_SERVER_AVAILABLE".equalsIgnoreCase(serverAddress)) {
                System.out.println("No server available. Exiting.");
                keepListening = false;
                return;
            }
            System.out.println("Directed to server at: " + serverAddress);

            String[] addressParts = serverAddress.split(":");
            String serverHost = addressParts[0];
            int serverPort = Integer.parseInt(addressParts[1]);

            // Connect to the server
            try (Socket serverSocket = new Socket(serverHost, serverPort);
                 BufferedReader serverIn = new BufferedReader(new InputStreamReader(serverSocket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter serverOut = new PrintWriter(new OutputStreamWriter(serverSocket.getOutputStream(), StandardCharsets.UTF_8), true);
                 BufferedReader userIn = new BufferedReader(new InputStreamReader(System.in))) {

                // Identify and register UDP port
                System.out.println("Identifying to server as: " + clientName);
                serverOut.println("hello " + clientName);
                int udpPort = udpSocket.getLocalPort();
                serverOut.println("udp " + udpPort);

                while (true) {
                    // Send user commands if available
                    if (userIn.ready()) {
                        String userInput = userIn.readLine();
                        if (userInput == null) break;
                        serverOut.println(userInput);
                        if ("quit".equalsIgnoreCase(userInput.trim())) {
                            keepListening = false;
                            break;
                        }
                    }

                    // Read server messages if available
                    if (serverIn.ready()) {
                        String line = serverIn.readLine();
                        if (line == null) break;

                        if (line.startsWith("FILE ")) {
                            receiveFile(line, serverIn);
                        } else {
                            System.out.println("Server says: " + line);
                        }
                    }

                    // light sleep to avoid busy spin if neither side is ready
                    try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                }
            }

        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        } finally {
            keepListening = false;
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
            }
            if (udpListenerThread != null) {
                try { udpListenerThread.join(); } catch (InterruptedException ignored) {}
            }
        }
    }

    // Receive Base64-encoded file until ENDFILE
    private static void receiveFile(String header, BufferedReader serverIn) throws IOException {
        // Header format: "FILE <name> <sizeBytes>"
        String[] parts = header.trim().split("\\s+");
        if (parts.length < 3) {
            System.out.println("Malformed FILE header: " + header);
            return;
        }
        String name = parts[1];
        long size = -1L;
        try { size = Long.parseLong(parts[2]); } catch (NumberFormatException ignore) {}

        String outName = "received_" + name;
        try (FileOutputStream fos = new FileOutputStream(outName)) {
            Base64.Decoder dec = Base64.getDecoder();
            long written = 0;
            while (true) {
                String b64 = serverIn.readLine();
                if (b64 == null) {
                    System.out.println("Unexpected end of stream during file transfer.");
                    break;
                }
                if ("ENDFILE".equals(b64)) break;
                byte[] chunk = dec.decode(b64);
                fos.write(chunk);
                written += chunk.length;
            }
            fos.flush();
            System.out.println("File received: " + outName + " (" + written + " bytes" + (size >= 0 ? " / expected " + size : "") + ")");
        } catch (IOException e) {
            System.out.println("File receive error: " + e.getMessage());
        }
    }

    private static void listenForUDP() {
        try {
            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while (keepListening) {
                udpSocket.receive(packet);
                String received = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                System.out.println("Received UDP packet: " + received);
            }
        } catch (SocketException se) {
            // closed during shutdown
        } catch (IOException e) {
            System.out.println("UDP listener error: " + e.getMessage());
        }
    }
}
