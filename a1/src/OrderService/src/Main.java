import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
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

        iscsIP = ((JSONObject) orderConfig.get("InterServiceCommunication")).getString("ip");
        iscsPort = ((JSONObject) orderConfig.get("InterServiceCommunication")).getInt("port");

        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.setExecutor(Executors.newFixedThreadPool(20));
        httpServer.createContext("/order", new OrderHandler());
        httpServer.createContext("/user", new UserHandler());
        httpServer.createContext("/product", new ProductHandler());
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

                    String iscsUrl = "http://" + iscsIP + ":" + iscsPort;
                    String userUrl = iscsUrl + "/user/" + userId;
                    String productUrl = iscsUrl + "/product/" + productId;

                    HttpURLConnection userConnection = (HttpURLConnection) new URL(userUrl).openConnection();
                    HttpURLConnection productConnection = (HttpURLConnection) new URL(productUrl).openConnection();
                    userConnection.setRequestMethod("GET");
                    productConnection.setRequestMethod("GET");
                    userConnection.setDoOutput(true);
                    productConnection.setDoOutput(true);

                    try (OutputStream outputStream = userConnection.getOutputStream()) {
                        outputStream.write("".getBytes(StandardCharsets.UTF_8));
                    }

                    try (OutputStream outputStream = productConnection.getOutputStream()) {
                        outputStream.write("".getBytes(StandardCharsets.UTF_8));
                    }

                    int userResponseCode = userConnection.getResponseCode();
                    int productResponseCode = productConnection.getResponseCode();

                    if (userResponseCode != 200 || productResponseCode != 200) {
                        exchange.sendResponseHeaders(400, 0);
                        exchange.close();
                        return;
                    }

                    JSONObject userResponse = new JSONObject(userConnection.getResponseMessage());
                    JSONObject productResponse = new JSONObject(productConnection.getResponseMessage());

                    if (productResponse.getInt("quantity") < quantity) {
                        exchange.sendResponseHeaders(400, 0);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write("Not enough quantity".getBytes(StandardCharsets.UTF_8));
                        }
                    }

                    String productUpdateUrl = iscsUrl + "/product/update";
                    HttpURLConnection productUpdateConnection = (HttpURLConnection) new URL(productUpdateUrl).openConnection();
                    productUpdateConnection.setRequestMethod("POST");
                    JSONObject productUpdateRequest = new JSONObject();
                    productUpdateRequest.put("id", productId);
                    productUpdateRequest.put("quantity", productResponse.getInt("quantity") - quantity);

                    try (OutputStream outputStream = productUpdateConnection.getOutputStream()) {
                        outputStream.write(productUpdateRequest.toString().getBytes(StandardCharsets.UTF_8));
                    }

                    int productUpdateResponseCode = productUpdateConnection.getResponseCode();
                    if (productUpdateResponseCode != 200) {
                        exchange.sendResponseHeaders(500, 0);
                        exchange.close();
                        return;
                    }

                    exchange.sendResponseHeaders(200, 0);
                    exchange.close();
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

    static class UserHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String targetUrl = "http://" + iscsIP + ":" + iscsPort + "/user";
            HttpURLConnection connection = (HttpURLConnection) new URL(targetUrl).openConnection();
            connection.setRequestMethod(exchange.getRequestMethod());
            connection.setDoOutput(true);

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(getRequestBody(exchange).getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            String responseMessage = connection.getResponseMessage();

            exchange.sendResponseHeaders(responseCode, responseMessage.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseMessage.getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        }
    }

    static class ProductHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String targetUrl = "http://" + iscsIP + ":" + iscsPort + "/product";
            HttpURLConnection connection = (HttpURLConnection) new URL(targetUrl).openConnection();
            connection.setRequestMethod(exchange.getRequestMethod());
            connection.setDoOutput(true);

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(getRequestBody(exchange).getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            String responseMessage = connection.getResponseMessage();

            exchange.sendResponseHeaders(responseCode, responseMessage.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseMessage.getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
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