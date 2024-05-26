import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadBalancer {
    private static final int CLIENT_PORT = 11114;  // Port to handle client requests
    private static final int REGISTRATION_PORT = 11115;  // Port to accept server registrations
    private ServerSocket clientSocket;
    private ServerSocket registrationSocket;
    private final List<ServerInfo> servers = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    public LoadBalancer() throws IOException {
        clientSocket = new ServerSocket(CLIENT_PORT);
        registrationSocket = new ServerSocket(REGISTRATION_PORT);
        System.out.println("Load Balancer running on ports " + CLIENT_PORT + " and " + REGISTRATION_PORT);
    }

    public void start() {
        new Thread(this::handleServerRegistrations).start();
        new Thread(this::handleClientRequests).start();
    }

    private void handleServerRegistrations() {
        try {
            while (true) {
                Socket serverSocket = registrationSocket.accept();
                BufferedReader in = new BufferedReader(new InputStreamReader(serverSocket.getInputStream()));
                PrintWriter out = new PrintWriter(serverSocket.getOutputStream(), true);
                String registrationMessage = in.readLine();
                if (registrationMessage != null && registrationMessage.startsWith("!join")) {
                    String[] parts = registrationMessage.split(" ");
                    int port = Integer.parseInt(parts[3]);  // Correct index to parse the port number
                    servers.add(new ServerInfo(serverSocket.getInetAddress().getHostAddress(), port));
                    out.println("!ack");
                    System.out.println("Registered server: " + serverSocket.getInetAddress().getHostAddress() + ":" + port);
                }
                serverSocket.close();
            }
        } catch (IOException e) {
            System.out.println("Error handling server registrations: " + e.getMessage());
        }
    }

    private void handleClientRequests() {
        try {
            while (true) {
                Socket client = clientSocket.accept();
                ServerInfo server = selectServer();
                if (server != null) {
                    PrintWriter out = new PrintWriter(client.getOutputStream(), true);
                    out.println(server.address + ":" + server.port);
                    client.close();
                    System.out.println("Client directed to: " + server.address + ":" + server.port);
                }
            }
        } catch (IOException e) {
            System.out.println("Error handling client requests: " + e.getMessage());
        }
    }

    private ServerInfo selectServer() {
        if (servers.isEmpty()) {
            System.out.println("No servers are currently registered.");
            return null;
        }
        return servers.get(roundRobinIndex.getAndIncrement() % servers.size());
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
        String address;
        int port;

        ServerInfo(String addr, int port) {
            this.address = addr;
            this.port = port;
        }
    }
}
