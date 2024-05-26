import java.io.*;
import java.net.*;
import java.util.Date;

public class Server {
    private static DatagramSocket udpSocket;
    private static final int PORT = 11112;
    private static final int UDP_PORT = 11113;
    private static volatile boolean streaming = false;

    public static void main(String[] args) throws IOException {
        System.out.println(STR."Server starting on port \{PORT}"); //used string template
        ServerSocket serverSocket = new ServerSocket(PORT);
        udpSocket = new DatagramSocket();

        while (true) {
            try {
                Socket client = serverSocket.accept();
                System.out.println(STR."Client connected: \{client.getInetAddress().getHostAddress()}");
                ClientHandler handler = new ClientHandler(client);
                new Thread(handler).start();
            } catch (IOException e) {
                System.out.println(STR."Connection Error: \{e.getMessage()}");
            }
        }
    }

    private static class ClientHandler implements Runnable {
        private final Socket clientSocket;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                 PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    String[] commands = inputLine.split(" ");
                    switch (commands[0].toLowerCase()) {
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
                            handleSendFile(commands, clientSocket);
                            break;
                        case "compute":
                            handleCompute(commands, out);
                            break;
                        case "stream":
                            streaming = true;
                            startStreaming(clientSocket.getInetAddress(), UDP_PORT);
                            break;
                        case "cancel":
                            streaming = false;
                            System.out.println("Streaming stopped by cancel command.");
                            break;
                        case "quit":
                            clientSocket.close();
                            return;
                        default:
                            out.println("Unknown command");
                            break;
                    }
                }
            } catch (IOException e) {
                System.out.println(STR."Error handling client: \{e.getMessage()}");
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    System.out.println(STR."Could not close a socket: \{e.getMessage()}");
                }
            }
        }
        private void startStreaming(InetAddress address, int port) {
            byte[] buffer = new byte[1024]; //1 KB of data
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, port);
            try {
                while (streaming) {
                    udpSocket.send(packet);
                    System.out.println("Streaming data...");
                    Thread.sleep(2000); // Simulate frame rate
                    //streaming = false;
                }
                System.out.println("Exiting streaming mode.");
            } catch (IOException | InterruptedException e) {
                System.out.println("Streaming interrupted: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
        }
    }
    private static String ls() {
        File dir = new File("."); // Current directory
        StringBuilder sb = new StringBuilder();
        File[] filesList = dir.listFiles();
        if (filesList != null) {
            for (File file : filesList) {
                sb.append(file.getName()).append(" ");
            }
        }
        return sb.toString().trim(); // Trim to remove the extra space at the end
    }

    private static String pwd() {
        return System.getProperty("user.dir");  // This Java system property provides the current working directory
    }

    private static void handleSendFile(String[] commands, Socket clientSocket) throws IOException {
        BufferedOutputStream bos = new BufferedOutputStream(clientSocket.getOutputStream());
        boolean verbose = commands.length > 2 && "-v".equals(commands[1]);
        String fileName = verbose ? commands[2] : commands[1];
        sendFile(fileName, bos, verbose);
    }

    private static void sendFile(String fileName, BufferedOutputStream bos, boolean verbose) throws IOException {
        File file = new File(fileName);
        if (!file.exists()) {
            PrintWriter out = new PrintWriter(bos, true);
            out.println("File not found");
            return;
        }

        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            byte[] bytes = new byte[4096];
            int count;
            while ((count = bis.read(bytes)) > 0) {
                bos.write(bytes, 0, count);
                if (verbose) {
                    System.out.println("Sent " + count + " bytes of " + fileName);
                }
            }
            bos.flush();  // Ensure all data is sent
        } catch (IOException e) {
            System.out.println("Error sending file: " + e.getMessage());
        }

        // Send EOF signal
        PrintWriter out = new PrintWriter(bos, true);
        out.println("EOF");
    }

    private static void handleCompute(String[] commands, PrintWriter out) {
        if (commands.length > 1) {
            int seconds = Integer.parseInt(commands[1]);
            compute(seconds);
            out.println("Computed for " + seconds + " seconds");
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





