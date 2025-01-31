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
import org.json.*;

public class Main {
    private static String iscsIP;
    private static int iscsPort;
    public static void main(String[] args) throws IOException {
        String configPath = args[0];
        JSONObject config = new JSONObject(new String(Files.readAllBytes(Paths.get(configPath))));
        JSONObject orderConfig = (JSONObject) config.get("OrderService");
        int port = orderConfig.getInt("port");

        iscsIP = ((JSONObject) config.get("InterServiceCommunication")).getString("ip");
        iscsPort = ((JSONObject) config.get("InterServiceCommunication")).getInt("port");

        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.setExecutor(Executors.newFixedThreadPool(20));
        httpServer.createContext("/order", new OrderHandler());
        httpServer.createContext("/user", new ProxyHandler());
        httpServer.createContext("/product", new ProxyHandler());
        httpServer.setExecutor(null);
        httpServer.start();
        System.out.println("Order Service started on port " + port);
    }

    static class OrderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (exchange.getRequestMethod().equals("POST")) {
                JSONObject requestJSON = getJSONObject(exchange);

                if (requestJSON.getString("command").equals("place order")) {
                    int userId = requestJSON.getInt("user_id");
                    int productId = requestJSON.getInt("product_id");
                    int quantity = requestJSON.getInt("quantity");

                    if (userId <= 0 || productId <= 0 || quantity <= 0) {
                        exchange.sendResponseHeaders(400, 0);
                        exchange.close();
                        return;
                    }

                    String iscsUrl = "http://" + iscsIP + ":" + iscsPort;
                    String userUrl = iscsUrl + "/user/" + userId;
                    String productUrl = iscsUrl + "/product/" + productId;

                    HttpURLConnection userConnection = (HttpURLConnection) new URL(userUrl).openConnection();
                    HttpURLConnection productConnection = (HttpURLConnection) new URL(productUrl).openConnection();
                    userConnection.setRequestMethod("GET");
                    productConnection.setRequestMethod("GET");
                    userConnection.setRequestProperty("Content-Type", "application/json");
                    productConnection.setRequestProperty("Content-Type", "application/json");
                    userConnection.setDoOutput(true);
                    productConnection.setDoOutput(true);

                    int userResponseCode = userConnection.getResponseCode();
                    int productResponseCode = productConnection.getResponseCode();

                    if (userResponseCode != 200 || productResponseCode != 200) {
                        exchange.sendResponseHeaders(400, 0);
                        exchange.close();
                        return;
                    }

                    JSONObject userResponse = new JSONObject(readInputStream(userConnection.getInputStream()));
                    JSONObject productResponse = new JSONObject(readInputStream(productConnection.getInputStream()));

                    if (productResponse.getInt("quantity") < quantity) {
                        exchange.sendResponseHeaders(400, 0);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write("Not enough quantity".getBytes(StandardCharsets.UTF_8));
                        }
                    }

                    String productUpdateUrl = iscsUrl + "/product/update";
                    HttpURLConnection productUpdateConnection = (HttpURLConnection) new URL(productUpdateUrl).openConnection();
                    productUpdateConnection.setRequestMethod("POST");
                    productUpdateConnection.setRequestProperty("Content-Type", "application/json");
                    JSONObject productUpdateRequest = new JSONObject();
                    productUpdateRequest.put("id", productId);
                    productUpdateRequest.put("quantity", productResponse.getInt("quantity") - quantity);
                    productUpdateConnection.setDoOutput(true);

                    try (OutputStream outputStream = productUpdateConnection.getOutputStream()) {
                        System.out.println("test");
                        outputStream.write(productUpdateRequest.toString().getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    }

                    int productUpdateResponseCode = productUpdateConnection.getResponseCode();
                    if (productUpdateResponseCode != 200) {
                        exchange.sendResponseHeaders(500, 0);
                        exchange.close();
                        return;
                    }

                    exchange.sendResponseHeaders(200, 0);
                    exchange.close();
                    return;
                }

                exchange.sendResponseHeaders(400, 0);
                exchange.close();
            } else {
                String response = "Invalid Request";
                exchange.sendResponseHeaders(400, response.length());
                exchange.getResponseBody().write(response.getBytes());
                exchange.close();
            }
        }
    }

    static class ProxyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String targetUrl = "http://" + iscsIP + ":" + iscsPort + exchange.getRequestURI().getPath();

            HttpURLConnection connection = (HttpURLConnection) new URL(targetUrl).openConnection();
            connection.setRequestMethod(exchange.getRequestMethod());

            if (exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                connection.setDoOutput(true);
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

    // copied from lecture code
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

    private static JSONObject getJSONObject(HttpExchange exchange) throws IOException {
        return new JSONObject(getRequestBody(exchange));
    }
}