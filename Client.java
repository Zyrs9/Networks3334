import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class Client {
    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 11112;
    private static final int UDP_PORT = 11113;
    private static DatagramSocket udpSocket;
    private static volatile boolean keepListening = true;

    public static void main(String[] args) {
        try {
            udpSocket = new DatagramSocket(UDP_PORT);
            Thread udpListenerThread = new Thread(Client::listenForUDP);
            udpListenerThread.start();

            try (Socket socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader serverIn = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                BufferedReader userIn = new BufferedReader(new InputStreamReader(System.in));
                String userInput;

                while (true) {
                    if (userIn.ready()) {  // Check if there's input from the user
                        userInput = userIn.readLine();
                        if ("quit".equalsIgnoreCase(userInput)) {
                            out.println(userInput);
                            keepListening = false;  // Stop the UDP listener
                            break;  // Exit the while loop and close the application
                        } else {
                            out.println(userInput);
                            if ("cancel".equalsIgnoreCase(userInput)) {
                                keepListening = false;  // Stop the UDP listener
                            } else {
                                keepListening = true;  // Ensure the listener is active unless cancel is called
                            }
                        }
                    }

                    if (serverIn.ready()) {  // Check if there's input from the server
                        System.out.println("Server says: " + serverIn.readLine());
                    }
                }
            }
            udpListenerThread.join(); // Wait for the UDP listener to finish
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
