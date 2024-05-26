import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class Client {
    private static final String LOAD_BALANCER_ADDRESS = "localhost";
    private static final int LOAD_BALANCER_PORT = 11114;
    private static DatagramSocket udpSocket;
    private static volatile boolean keepListening = true;

    public static void main(String[] args) {
        try {
            udpSocket = new DatagramSocket();
            Thread udpListenerThread = new Thread(Client::listenForUDP);
            udpListenerThread.start();

            try (Socket lbSocket = new Socket(LOAD_BALANCER_ADDRESS, LOAD_BALANCER_PORT);
                 BufferedReader lbIn = new BufferedReader(new InputStreamReader(lbSocket.getInputStream()));
                 PrintWriter lbOut = new PrintWriter(lbSocket.getOutputStream(), true)) {

                String serverAddress = lbIn.readLine().replace("/", "").trim();
                System.out.println("Directed to connect to server at: " + serverAddress);

                String[] addressParts = serverAddress.split(":");
                try (Socket serverSocket = new Socket(addressParts[0], Integer.parseInt(addressParts[1]));
                     BufferedReader serverIn = new BufferedReader(new InputStreamReader(serverSocket.getInputStream()));
                     PrintWriter serverOut = new PrintWriter(serverSocket.getOutputStream(), true);
                     BufferedReader userIn = new BufferedReader(new InputStreamReader(System.in))) {

                    String userInput;
                    while (true) {
                        if (userIn.ready()) {
                            userInput = userIn.readLine();
                            serverOut.println(userInput);
                            if ("quit".equalsIgnoreCase(userInput)) {
                                keepListening = false;
                                break;
                            }
                        }

                        if (serverIn.ready()) {
                            System.out.println("Server says: " + serverIn.readLine());
                        }
                    }
                }
            }
            udpListenerThread.join();
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        } finally {
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
            }
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
        } catch (IOException e) {
            System.out.println("UDP socket closed or error: " + e.getMessage());
        }
    }
}
