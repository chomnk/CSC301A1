
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.json.JSONObject;

import java.sql.SQLException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private static final String DB_URL = "jdbc:sqlite:./my_database.db";
    private static Connection connection;
    public static void main(String[] args) throws IOException {
        String configPath = args[0];
        JSONObject config = new JSONObject(new String(Files.readAllBytes(Paths.get(configPath))));
        JSONObject orderConfig = (JSONObject) config.get("ProductService");
        int port = orderConfig.getInt("port");
        initDatabase();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.setExecutor(Executors.newFixedThreadPool(20)); // Adjust the pool size as needed

        server.createContext("/product", new ProductHandler());

        server.setExecutor(null);

        server.start();

        System.out.println("Server started on port " + port);
    }

    private static void initDatabase() {
        try {
            connection = DriverManager.getConnection(DB_URL);

            String createTableSQL = """
                CREATE TABLE IF NOT EXISTS products (
                    id INTEGER PRIMARY KEY,
                    productname VARCHAR(255),
                    price FLOAT,
                    quantity INT
                );
            """;

            try (Statement statement = connection.createStatement()) {
                statement.execute(createTableSQL);
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    static class ProductHandler implements HttpHandler {
        private static final Gson gson = new Gson();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestMethod = exchange.getRequestMethod();

            if ("GET".equalsIgnoreCase(requestMethod)) {
                handleGet(exchange);
            } else if ("POST".equalsIgnoreCase(requestMethod)) {
                handlePost(exchange);
            } else {
                exchange.close();
            }
        }

        public void handlePost(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String requestBody = getRequestBody(exchange);
                try {
                    JsonObject json = gson.fromJson(requestBody, JsonObject.class);

                    if (json.has("command")) {
                        String command = json.get("command").getAsString();
                        if (json.has("id")) {
                            int id = json.get("id").getAsInt();
                            String productName = json.has("productname") ? json.get("productname").getAsString() : null;
                            Double price = json.has("price") ? json.get("price").getAsDouble() : null;
                            Integer quantity = json.has("quantity") ? json.get("quantity").getAsInt() : null;

                            boolean success = false;

                            switch (command) {
                                case "create":
                                    if (productName != null && price != null && quantity != null) {
                                        success = create(id, productName, price, quantity);
                                    } else {
                                        sendResponse(exchange, "Missing required fields for creation.");
                                    }
                                    break;
                                case "update":
                                    success = update(id, productName, price, quantity);
                                    break;
                                case "delete":
                                    success = delete(id, productName, price, quantity);
                                    break;
                            }

                            if (success) {
                                sendResponse(exchange, "Success.");
                            } else {
                                sendResponse(exchange, "Failed.");
                            }
                        } else {
                            sendResponse(exchange, "No ID Provided.");
                        }
                    } else {
                        sendResponse(exchange, "Command Error.");
                    }
                } catch (Exception e) {
                    sendResponse(exchange, "Error.");
                }
            } else {
                // Send a 405 Method Not Allowed response for non-POST requests
                exchange.sendResponseHeaders(405, 0);
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

        private static boolean create(int id, String name, double price, int quantity) {
            String sql = "INSERT INTO products (id, productname, price, quantity) VALUES (?, ?, ?, ?);";
            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement statement = conn.prepareStatement(sql)) {

                statement.setInt(1, id);
                statement.setString(2, name);
                statement.setDouble(3, price);
                statement.setInt(4, quantity);

                int affectedRows = statement.executeUpdate();
                return affectedRows > 0;
            } catch (SQLException e) {
                return false;
            }
        }

        private static boolean update(int id, String productname, Double price, Integer quantity) {
            if (productname == null && price == null && quantity == null) { return false; }
            StringBuilder sql = new StringBuilder("UPDATE products SET ");
            if (productname != null) { sql.append("productname").append(" = ?, "); }
            if (price != null) { sql.append("price").append(" = ?, "); }
            if (quantity != null) { sql.append("quantity").append(" = ?, "); }
            sql.setLength(sql.length() - 2);
            sql.append(" WHERE id = ?;");

            String query = sql.toString();
            System.out.println(query);
            int count = 1;

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement statement = conn.prepareStatement(query)) {

                if (productname != null) { statement.setString(count, productname); count++; }
                if (price != null) { statement.setDouble(count, price); count++; }
                if (quantity != null) { statement.setInt(count, quantity); count++; }

                statement.setInt(count, id);

                int affectedRows = statement.executeUpdate();
                return affectedRows > 0;
            } catch (SQLException e) {
                return false;
            }
        }

        private static boolean delete(int id, String name, Double price, Integer quantity) {
            String check_sql = "SELECT COUNT(*) FROM products WHERE id = ? AND productname = ? AND price = ? AND quantity = ?";
            String delete_sql = "DELETE FROM products WHERE id = ?";

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement check = conn.prepareStatement(check_sql);
                 PreparedStatement delete = conn.prepareStatement(delete_sql)) {

                // Set parameters for the existence check
                check.setInt(1, id);
                check.setString(2, name);
                check.setDouble(3, price);
                check.setInt(4, quantity);

                // Execute the query
                ResultSet result = check.executeQuery();
                if (result.next() && result.getInt(1) > 0) {
                    delete.setInt(1, id);
                    int affectedRows = delete.executeUpdate();
                    return affectedRows > 0;
                } else {
                    return false;
                }
            } catch (SQLException e) {
                return false;
            }
        }

        public void handleGet(HttpExchange exchange) throws IOException {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String requestURI = exchange.getRequestURI().toString();
                int ID = -1;

                Pattern pattern = Pattern.compile("^/product/(\\d+)$");
                Matcher matcher = pattern.matcher(requestURI);
                if (matcher.find()) {
                    ID = Integer.parseInt(matcher.group(1));
                } else {
                    sendResponse(exchange, "ID Format Error");
                }

                JsonObject item = new JsonObject();

                String sql = "SELECT id, productname, price, quantity FROM products WHERE id = ?";
                try (Connection conn = DriverManager.getConnection(DB_URL);
                     PreparedStatement statement = conn.prepareStatement(sql)) {
                    statement.setInt(1, ID);
                    ResultSet result = statement.executeQuery();
                    if (result.next()) {
                        item.addProperty("id", result.getInt("id"));
                        item.addProperty("productname", result.getString("productname"));
                        item.addProperty("price", result.getDouble("price"));
                        item.addProperty("quantity", result.getInt("quantity"));
                    }
                } catch (SQLException e) {
                    sendResponse(exchange, "SQL Error.");
                }

                String response = gson.toJson(item);
                sendResponse(exchange, response);
            } else {
                exchange.sendResponseHeaders(405,0);
                exchange.close();
            }
        }
    }

    private static void sendResponse(HttpExchange exchange, String response) throws IOException {
        exchange.sendResponseHeaders(200, response.length());
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes(StandardCharsets.UTF_8));
        os.close();
    }
}

