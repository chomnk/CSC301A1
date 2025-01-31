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

                if (!requestJSON.has("command") || !(requestJSON.get("command") instanceof String)) {
                    exchange.sendResponseHeaders(400, -1);
                    exchange.close();
                    return;
                }

                if (requestJSON.getString("command").equals("place order")) {

                    if (!validRequest(requestJSON)) {
                        JSONObject response = new JSONObject();
                        response.put("status", "Invalid Request");
                        exchange.sendResponseHeaders(400, 0);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(response.toString().getBytes(StandardCharsets.UTF_8));
                        }
                    }

                    int userId = requestJSON.getInt("user_id");
                    int productId = requestJSON.getInt("product_id");
                    int quantity = requestJSON.getInt("quantity");

                    if (userId <= 0 || productId <= 0 || quantity <= 0) {
                        JSONObject response = new JSONObject();
                        response.put("status", "Invalid Request");
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(response.toString().getBytes(StandardCharsets.UTF_8));
                        }
                    }

                    String iscsUrl = "http://" + iscsIP + ":" + iscsPort;
                    String userUrl = iscsUrl + "/user/" + userId;
                    String productUrl = iscsUrl + "/product/" + productId;

                    HttpURLConnection userConnection = null;
                    try {
                        userConnection = (HttpURLConnection) new URI(userUrl).toURL().openConnection();
                    } catch (URISyntaxException e) {
                        e.printStackTrace();
                        exchange.sendResponseHeaders(500, -1);
                    }
                    if (userConnection == null) {
                        exchange.sendResponseHeaders(500, -1);
                        return;
                    }
                    HttpURLConnection productConnection = null;
                    try {
                        productConnection = (HttpURLConnection) new URI(productUrl).toURL().openConnection();
                    } catch (URISyntaxException e) {
                        e.printStackTrace();
                        exchange.sendResponseHeaders(500, -1);
                    }
                    if (productConnection == null) {
                        exchange.sendResponseHeaders(500, -1);
                        return;
                    }
                    userConnection.setRequestMethod("GET");
                    productConnection.setRequestMethod("GET");
                    userConnection.setRequestProperty("Content-Type", "application/json");
                    productConnection.setRequestProperty("Content-Type", "application/json");

                    int userResponseCode = userConnection.getResponseCode();
                    int productResponseCode = productConnection.getResponseCode();

                    if (userResponseCode != 200 || productResponseCode != 200) {
                        JSONObject response = new JSONObject();
                        response.put("status", "Invalid Request");
                        exchange.sendResponseHeaders(404, 0);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(response.toString().getBytes(StandardCharsets.UTF_8));
                        }
                    }

                    JSONObject userResponse = new JSONObject(readInputStream(userConnection.getInputStream()));
                    JSONObject productResponse = new JSONObject(readInputStream(productConnection.getInputStream()));

                    if (productResponse.getInt("quantity") < quantity) {
                        JSONObject response = new JSONObject();
                        response.put("status", "Exceeded quantity limit");
                        exchange.sendResponseHeaders(400, 0);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(response.toString().getBytes(StandardCharsets.UTF_8));
                        }
                    }

                    String productUpdateUrl = iscsUrl + "/product";
                    HttpURLConnection productUpdateConnection = null;
                    try {
                        productUpdateConnection = (HttpURLConnection) new URI(productUpdateUrl).toURL().openConnection();
                    } catch (URISyntaxException e) {
                        throw new RuntimeException(e);
                    }
                    productUpdateConnection.setRequestMethod("POST");
                    productUpdateConnection.setRequestProperty("Content-Type", "application/json");
                    JSONObject productUpdateRequest = new JSONObject();
                    productUpdateRequest.put("command", "update");
                    productUpdateRequest.put("id", productId);
                    int newQuantity = productResponse.getInt("quantity") - quantity;
                    productUpdateRequest.put("quantity", newQuantity);
                    productUpdateConnection.setDoOutput(true);

                    try (OutputStream outputStream = productUpdateConnection.getOutputStream()) {
                        outputStream.write(productUpdateRequest.toString().getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    }

                    int productUpdateResponseCode = productUpdateConnection.getResponseCode();
                    if (productUpdateResponseCode != 200) {
                        exchange.sendResponseHeaders(500, 0);
                        exchange.close();
                        return;
                    }

                    JSONObject response = new JSONObject();
                    response.put("status", "Success");
                    response.put("product_id", productId);
                    response.put("user_id", userId);
                    response.put("quantity", quantity);

                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    exchange.close();
                    return;
                }

                JSONObject response = new JSONObject();
                response.put("status", "Invalid Request");
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.toString().getBytes(StandardCharsets.UTF_8));
                }
            } else {
                JSONObject response = new JSONObject();
                response.put("status", "Invalid Request");
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
        }

        private boolean validRequest(JSONObject requestJSON) {
            return requestJSON.has("user_id") && requestJSON.has("product_id") && requestJSON.has("quantity")
                    && requestJSON.get("user_id") instanceof Integer && requestJSON.get("product_id") instanceof Integer
                    && requestJSON.get("quantity") instanceof Integer && requestJSON.getInt("quantity") > 0;
        }
    }

    static class ProxyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String targetUrl = "http://" + iscsIP + ":" + iscsPort + exchange.getRequestURI().getPath();

            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URI(targetUrl).toURL().openConnection();
            } catch (URISyntaxException e) {
                e.printStackTrace();
                exchange.sendResponseHeaders(500, -1);
            }
            if (connection == null) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
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