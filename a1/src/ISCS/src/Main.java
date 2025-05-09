import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Main class to start the ISCS.
 */
public class Main {
    /**
     * IP address of the User Service.
     */
    private static String userServiceIP;

    /**
     * Port number of the User Service.
     */
    private static int userServicePort;

    /**
     * IP address of the Product Service.
     */
    private static String productServiceIP;

    /**
     * Port number of the Product Service.
     */
    private static int productServicePort;

    /**
     * Main method to start the server.
     *
     * @param args Command line arguments, expects the path to the configuration file.
     * @throws IOException If an I/O error occurs.
     */
    public static void main(String[] args) throws IOException {
        String configPath = args[0];
        JSONObject config = new JSONObject(new String(Files.readAllBytes(Paths.get(configPath))));
        JSONObject iscsConfig = config.getJSONObject("InterServiceCommunication");
        int port = iscsConfig.getInt("port");

        JSONObject userConfig = config.getJSONObject("UserService");
        userServiceIP = userConfig.getString("ip");
        userServicePort = userConfig.getInt("port");

        JSONObject productConfig = config.getJSONObject("ProductService");
        productServiceIP = productConfig.getString("ip");
        productServicePort = productConfig.getInt("port");

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(20));

        server.createContext("/user", new ProxyHandler(userServiceIP, userServicePort));
        server.createContext("/product", new ProxyHandler(productServiceIP, productServicePort));

        server.start();
    }

    /**
     * Handles HTTP requests by proxying them to the appropriate service.
     */
    static class ProxyHandler implements HttpHandler {
        /**
         * The IP address of the target service.
         */
        private final String targetIP;

        /**
         * The port of the target service.
         */
        private final int targetPort;

        /**
         * Constructor for ProxyHandler.
         *
         * @param targetIP The IP address of the target service.
         * @param targetPort The port of the target service.
         */
        public ProxyHandler(String targetIP, int targetPort) {
            this.targetIP = targetIP;
            this.targetPort = targetPort;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String targetUrl = "http://" + targetIP + ":" + targetPort + exchange.getRequestURI().toString();

            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) (new URI(targetUrl)).toURL().openConnection();
            } catch (URISyntaxException e) {
                e.printStackTrace();
                exchange.sendResponseHeaders(500, -1);
            }
            if (connection == null) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            connection.setRequestMethod(method);
            connection.setDoOutput(true);

            if ("POST".equalsIgnoreCase(method)) {
                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(getRequestBody(exchange).getBytes(StandardCharsets.UTF_8));
                }
            }

            int responseCode = connection.getResponseCode();
            exchange.sendResponseHeaders(responseCode, 0);

            if (connection.getContentLength() != 0) {
                String responseMessage = responseCode >= 200 && responseCode < 400 ? readInputStream(connection.getInputStream()) : readInputStream(connection.getErrorStream());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseMessage.getBytes(StandardCharsets.UTF_8));
                }
            }
            exchange.close();
        }
    }

    /**
     * Reads the request body from the exchange and returns it as a string.
     *
     * @param exchange The HTTP exchange.
     * @return The request body as a string.
     * @throws IOException If an I/O error occurs.
     */
    private static String getRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder requestBody = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                requestBody.append(line);
            }
            return requestBody.toString();
        }
    }

    /**
     * Reads the input stream and returns the content as a string.
     *
     * @param inputStream The input stream to read.
     * @return The content of the input stream as a string.
     * @throws IOException If an I/O error occurs.
     */
    private static String readInputStream(InputStream inputStream) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }
}