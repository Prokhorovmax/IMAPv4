
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class Server extends Thread {

    private int port;
    private ServerSocket serverSocket = null;


    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("ADD PORT NUMBER AS INPUT ARGUMENT.");
            System.exit(-1);
        } else {
            Server server = new Server(Integer.parseInt(args[0]));
            server.initialize();
        }
    }

    private Server(int host_port) {
        this.port = host_port;
    }

    private void initialize() {
        System.out.println("SERVER STARTED WORKING ON PORT " + port);
        // server socket
        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            System.err.println("CANNOT LISTEN TO THE PORT " + port);
            System.exit(-1);
        }

        // connecting new client
        System.out.println("WAITING FOR CLIENT CONNECTION...");
        while (true) {
            Socket clientSocket = null;
            try {
                clientSocket = serverSocket.accept();
                System.out.println("NEW CLIENT CONNECTED.");
            } catch (IOException e) {
                System.err.println("CANNOT ACCEPT NEW CLIENT!");
                System.out.println("CLOSING SERVER.");
                closeServer();
                System.exit(-1);
            }

            Thread clientThread = new Thread(new Handler(clientSocket));
            clientThread.start();
        }
    }

    private void closeServer() {
        try {
            serverSocket.close();
        } catch (IOException e) {
            System.err.println("CANNOT CLOSE SERVER SOCKET!");
            System.exit(-1);
        }
        System.out.println("SERVER STOPPED WORKING");
    }
}
