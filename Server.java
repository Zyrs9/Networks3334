import java.io.*;
import java.net.*;
import java.util.Date;

public class Server {
    private static final int PORT = 11112;
    private static final int UDP_PORT = 11113;
    private static final int LOAD_BALANCER_REGISTRATION_PORT = 11115;
    private static final String LOAD_BALANCER_HOST = "localhost";
    private static DatagramSocket udpSocket;
    private static volatile boolean streaming = false;

    public static void main(String[] args) throws IOException {
        registerWithLoadBalancer();
        udpSocket = new DatagramSocket(UDP_PORT);
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("Server listening on port: " + PORT);

        while (true) {
            try {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Accepted connection from client: " + clientSocket.getRemoteSocketAddress());
                ClientHandler handler = new ClientHandler(clientSocket);
                new Thread(handler).start();
            } catch (IOException e) {
                System.out.println("Connection Error: " + e.getMessage());
            }
        }
    }

    private static void registerWithLoadBalancer() {
        try (Socket socket = new Socket(LOAD_BALANCER_HOST, LOAD_BALANCER_REGISTRATION_PORT);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            out.println("!join -v dynamic " + PORT);
            String response = in.readLine();
            if ("!ack".equals(response)) {
                System.out.println("Successfully registered with Load Balancer.");
            }
        } catch (IOException e) {
            System.out.println("Could not connect to Load Balancer: " + e.getMessage());
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
                        case "time": out.println(new Date()); break;
                        case "ls": out.println(ls()); break;
                        case "pwd": out.println(pwd()); break;
                        case "sendfile": handleSendFile(commands, clientSocket); break;
                        case "compute": handleCompute(commands, out); break;
                        case "quit": clientSocket.close(); return;
                        case "stream": streaming = true; startStreaming(clientSocket.getInetAddress()); break;
                        case "cancel": streaming = false; System.out.println("Streaming stopped by cancel command."); break;
                        default: out.println("Unknown command"); break;
                    }
                }
            } catch (IOException e) {
                System.out.println("Error handling client: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    System.out.println("Could not close a socket: " + e.getMessage());
                }
            }
        }

        private void startStreaming(InetAddress address) {
            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, UDP_PORT);
            try {
                int i = 0;
                while (streaming) {
                    udpSocket.send(packet);
                    System.out.println("Streaming data...");
                    Thread.sleep(2000);
                    if (i > 30) streaming = false;
                    i++;
                }
                System.out.println("Exiting streaming mode.");
            } catch (IOException | InterruptedException e) {
                System.out.println("Streaming interrupted: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
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
                bos.flush();
            } catch (IOException e) {
                System.out.println("Error sending file: " + e.getMessage());
            }

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
}
//Author Mert.