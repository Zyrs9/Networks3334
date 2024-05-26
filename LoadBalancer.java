import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadBalancer {
    private static final int CLIENT_PORT = 11114;  // Port for client connections
    private static final int REGISTRATION_PORT = 11115;  // Port for server registrations
    private final ServerSocket clientSocket;
    private final ServerSocket serverRegSocket;
    private final List<ServerInfo> servers = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    public LoadBalancer() throws IOException {
        clientSocket = new ServerSocket(CLIENT_PORT);
        serverRegSocket = new ServerSocket(REGISTRATION_PORT);
    }

    public void start() {
        new Thread(this::handleServerRegistrations).start();
        new Thread(this::handleClientRequests).start();
    }

    private void handleServerRegistrations() {
        try {
            while (true) {
                Socket server = serverRegSocket.accept();
                registerServer(server);
            }
        } catch (IOException e) {
            System.out.println(STR."Error handling server registration: \{e.getMessage()}");
        }
    }

    private void registerServer(Socket server) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(server.getInputStream()));
            PrintWriter out = new PrintWriter(server.getOutputStream(), true);
            out.println("!ack");
            String registrationMessage = in.readLine();
            if (registrationMessage != null && registrationMessage.startsWith("!join")) {
                String[] parts = registrationMessage.split(" ");
                String method = parts.length > 2 ? parts[2] : "dynamic"; // Default to dynamic balancing
                servers.add(new ServerInfo(server, method));
                System.out.println(STR."Registered server: \{server.getRemoteSocketAddress()} with \{method} method.");
            }
        } catch (IOException e) {
            System.out.println(STR."Error registering server: \{e.getMessage()}");
        }
    }

    private void handleClientRequests() {
        try {
            while (true) {
                Socket client = clientSocket.accept();
                ServerInfo server = selectServer();
                if (server != null) {
                    PrintWriter out = new PrintWriter(client.getOutputStream(), true);
                    out.println(STR."Connect to server at: \{server.server.getRemoteSocketAddress()}");
                    client.close();
                }
            }
        } catch (IOException e) {
            System.out.println(STR."Error handling client request: \{e.getMessage()}");
        }
    }

    private ServerInfo selectServer() {
        if (servers.isEmpty()) {
            return null;
        }
        int index = roundRobinIndex.getAndIncrement() % servers.size();
        return servers.get(index);
    }

    public static void main(String[] args) {
        try {
            LoadBalancer lb = new LoadBalancer();
            lb.start();
        } catch (IOException e) {
            System.out.println(STR."Failed to start Load Balancer: \{e.getMessage()}");
        }
    }

    static class ServerInfo {
        Socket server;
        String method;

        ServerInfo(Socket server, String method) {
            this.server = server;
            this.method = method;
        }
    }
}
