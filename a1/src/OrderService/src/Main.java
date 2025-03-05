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
import java.sql.*;
import java.util.concurrent.Executors;
import org.json.*;

/**
 * Main class to start the Order Service.
 */
public class Main {
    /**
     * URL for the SQLite database.
     */
    private static final String DB_URL = "jdbc:sqlite:./compiled/order.db";

    /**
     * IP address for the Inter-Service Communication System (ISCS).
     */
    private static String iscsIP;

    /**
     * Port number for the Inter-Service Communication System (ISCS).
     */
    private static int iscsPort;

    /**
     * Main method to start the server.
     *
     * @param args Command line arguments, expects the path to the configuration file.
     * @throws IOException If an I/O error occurs.
     */
    public static void main(String[] args) throws IOException {
        String configPath = args[0];
        JSONObject config = new JSONObject(new String(Files.readAllBytes(Paths.get(configPath))));
        JSONObject orderConfig = (JSONObject) config.get("OrderService");
        int port = orderConfig.getInt("port");

        iscsIP = ((JSONObject) config.get("InterServiceCommunication")).getString("ip");
        iscsPort = ((JSONObject) config.get("InterServiceCommunication")).getInt("port");

        initDatabase();

        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.setExecutor(Executors.newFixedThreadPool(20));
        httpServer.createContext("/order", new OrderHandler());
        httpServer.createContext("/user", new ProxyHandler());
        httpServer.createContext("/product", new ProxyHandler());

        httpServer.createContext("/user/purchased", new PurchasedHandler());

        httpServer.setExecutor(null);
        httpServer.start();
        System.out.println("Order Service started on port " + port);
    }

    /**
     * Initializes the database by creating the necessary tables.
     */
    private static void initDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            String createTableSQL = """
                CREATE TABLE IF NOT EXISTS purchases (
                    user_id INTEGER,
                    product_id INTEGER,
                    quantity INTEGER,
                    PRIMARY KEY(user_id, product_id)
                );
            """;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createTableSQL);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Handles HTTP requests for order-related operations.
     */
    static class OrderHandler implements HttpHandler {

        /**
         * Updates the purchase record in the database.
         *
         * @param userId    The ID of the user.
         * @param productId The ID of the product.
         * @param quantity  The quantity of the product.
         */
        private static void updatePurchaseRecord(int userId, int productId, int quantity) {
            try (Connection conn = DriverManager.getConnection(DB_URL)) {
                String updateSQL = "UPDATE purchases SET quantity = quantity + ? WHERE user_id = ? AND product_id = ?;";
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSQL)) {
                    updateStmt.setInt(1, quantity);
                    updateStmt.setInt(2, userId);
                    updateStmt.setInt(3, productId);
                    int rowsAffected = updateStmt.executeUpdate();
                    if (rowsAffected == 0) {
                        String insertSQL = "INSERT INTO purchases (user_id, product_id, quantity) VALUES (?, ?, ?);";
                        try (PreparedStatement insertStmt = conn.prepareStatement(insertSQL)) {
                            insertStmt.setInt(1, userId);
                            insertStmt.setInt(2, productId);
                            insertStmt.setInt(3, quantity);
                            insertStmt.executeUpdate();
                        }
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

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

                    updatePurchaseRecord(userId, productId, quantity);

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

        /**
         * Validates the request JSON.
         *
         * @param requestJSON The request JSON object.
         * @return True if the request is valid, false otherwise.
         */
        private boolean validRequest(JSONObject requestJSON) {
            return requestJSON.has("user_id") && requestJSON.has("product_id") && requestJSON.has("quantity")
                    && requestJSON.get("user_id") instanceof Integer && requestJSON.get("product_id") instanceof Integer
                    && requestJSON.get("quantity") instanceof Integer && requestJSON.getInt("quantity") > 0;
        }
    }

    /**
     * Handles HTTP requests for retrieving purchased products.
     */
    static class PurchasedHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String[] segments = path.split("/");
            if (segments.length != 4) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }

            int userId;
            try {
                userId = Integer.parseInt(segments[3]);
            } catch (NumberFormatException e) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }

            String userUrl = "http://" + iscsIP + ":" + iscsPort + "/user/" + userId;
            HttpURLConnection userConnection;
            try {
                userConnection = (HttpURLConnection) new URI(userUrl).toURL().openConnection();
            } catch (URISyntaxException e) {
                e.printStackTrace();
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            userConnection.setRequestMethod("GET");
            int userResponseCode = userConnection.getResponseCode();
            if (userResponseCode != 200) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            JSONObject responseJSON = new JSONObject();
            try (Connection conn = DriverManager.getConnection(DB_URL)) {
                String querySQL = "SELECT product_id, quantity FROM purchases WHERE user_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(querySQL)) {
                    stmt.setInt(1, userId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            int productId = rs.getInt("product_id");
                            int qty = rs.getInt("quantity");
                            responseJSON.put(String.valueOf(productId), qty);
                        }
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }

            byte[] responseBytes = responseJSON.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
            exchange.close();
        }
    }

    /**
     * Handles HTTP requests by proxying them to the appropriate service.
     */
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

    // copied from lecture code
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
     * Reads the request body from the exchange and returns it as a JSON object.
     *
     * @param exchange The HTTP exchange.
     * @return The request body as a JSON object.
     * @throws IOException If an I/O error occurs.
     */
    private static JSONObject getJSONObject(HttpExchange exchange) throws IOException {
        return new JSONObject(getRequestBody(exchange));
    }
}