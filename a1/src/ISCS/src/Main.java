import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class Main {
    private static String userServiceIP;
    private static int userServicePort;
    private static String productServiceIP;
    private static int productServicePort;

    public static void main(String[] args) throws IOException {
        String configPath = args[0];
        JSONObject config = new JSONObject(new String(Files.readAllBytes(Paths.get(configPath))));
        JSONObject iscsConfig = config.getJSONObject("ISCS");
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

    static class ProxyHandler implements HttpHandler {
        private final String targetIP;
        private final int targetPort;

        public ProxyHandler(String targetIP, int targetPort) {
            this.targetIP = targetIP;
            this.targetPort = targetPort;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String targetUrl = "http://" + targetIP + ":" + targetPort + exchange.getRequestURI().toString();

            HttpURLConnection connection = (HttpURLConnection) new URL(targetUrl).openConnection();
            connection.setRequestMethod(method);
            connection.setDoOutput(true);

            if ("POST".equalsIgnoreCase(method)) {
                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(getRequestBody(exchange).getBytes(StandardCharsets.UTF_8));
                }
            }

            int responseCode = connection.getResponseCode();
            String responseMessage = readResponse(connection);

            exchange.sendResponseHeaders(responseCode, responseMessage.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseMessage.getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        }
    }

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

    private static String readResponse(HttpURLConnection connection) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }
}