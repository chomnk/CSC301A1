
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
                    description VARCHAR(255),
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
            JsonObject empty = new JsonObject();
            String emptyResponse = gson.toJson(empty);

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String requestBody = getRequestBody(exchange);
                try {
                    JsonObject json = gson.fromJson(requestBody, JsonObject.class);

                    if (json.has("command")) {
                        String command = json.get("command").getAsString();
                        if (json.has("id")) {
                            Integer id = json.has("id") ? json.get("id").getAsInt() : null;
                            String productName = json.has("name") ? json.get("name").getAsString() : null;
                            String description = json.has("description") ? json.get("description").getAsString() : null;
                            Double price = json.has("price") ? json.get("price").getAsDouble() : null;

                            if (!json.has("quantity") || !json.get("quantity").isJsonPrimitive() ||
                                    !json.get("quantity").getAsJsonPrimitive().isNumber() ||
                                    json.get("quantity").getAsDouble() % 1 != 0) {

                                System.out.println(5);
                                sendResponse(exchange, 400, "{}");
                                return;
                            }

                            Integer quantity = json.get("quantity").getAsInt();

                            switch (command) {
                                case "create" -> {
                                    if (
                                            (productName != null && productName.isEmpty())
                                            || description == null
                                            || (quantity != null && quantity < 0)
                                            || (price != null && price < 0)
                                    ) {
                                        sendResponse(exchange, 400, emptyResponse);
                                    } else {
                                        create(exchange, id, productName, description, price, quantity);
                                    }
                                }

                                case "update" -> {
                                    if (
                                            id == null
                                            || (description != null && description.isEmpty())
                                            || (price != null && price < 0)
                                            || (quantity != null && quantity < 0)
                                    ) {
                                        sendResponse(exchange, 400, emptyResponse);
                                    } else {
                                        update(exchange, id, productName, description, price, quantity);
                                    }
                                }

                                case "delete" -> {
                                    if (
                                            productName != null // missing name field
                                            && id != null // invalid id(actually in handler)
                                    ) {
                                        delete(exchange, id, productName, description, price, quantity);
                                    } else {
                                        sendResponse(exchange, 400, emptyResponse);
                                    }
                                }
                                case "info" -> {
                                    info(exchange, id, productName, description, price, quantity);
                                }
                            }
                        } else {
                            sendResponse(exchange, 400, emptyResponse);
                        }
                    } else {
                        sendResponse(exchange, 404, "Command Error.");
                    }
                } catch (Exception e) {
                    sendResponse(exchange, 404, "Error.");
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

        private static void info(HttpExchange exchange, int id, String name, String description, double price, int quantity) {
            String sql = "SELECT description FROM products WHERE id = ?";

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement statement = conn.prepareStatement(sql)) {

                statement.setInt(1, id);
                ResultSet result = statement.executeQuery();

                if (result.next()) {
                    String productDescription = result.getString("description");

                    JsonObject item = new JsonObject();
                    item.addProperty("description", productDescription);
                    String response = gson.toJson(item);
                    try {
                        sendResponse(exchange, 200, response);
                    } catch (IOException e) {
                        System.out.println(e);
                    }
                } else {
                    System.out.println("Product not found.");
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        private static void create(HttpExchange exchange, Integer id, String name, String description, double price, int quantity) {
            String sql = "INSERT INTO products (id, productname, description, price, quantity) VALUES (?, ?, ?, ?, ?);";
            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement statement = conn.prepareStatement(sql)) {

                statement.setInt(1, id);
                statement.setString(2, name);
                statement.setString(3, description);
                statement.setDouble(4, price);
                statement.setInt(5, quantity);

                int affectedRows = statement.executeUpdate();
                if (affectedRows > 0) {
                    JsonObject item = new JsonObject();
                    item.addProperty("id", id);
                    item.addProperty("name", name);
                    item.addProperty("description", description);
                    item.addProperty("price", price);
                    item.addProperty("quantity", quantity);
                    String response = gson.toJson(item);
                    try {
                        sendResponse(exchange, 200, response);
                    } catch (IOException e) {
                        System.out.println(e);
                    }
                }
            } catch (SQLException e) {
                System.out.println(e);
                JsonObject item = new JsonObject();
                String response = gson.toJson(item);
                try {
                    sendResponse(exchange, 409, response);
                } catch (IOException e1) {
                    System.out.println(e1);
                }
            }
        }

        private static void update(HttpExchange exchange, int id, String productname, String description, Double price, Integer quantity) {
            JsonObject empty = new JsonObject();
            String emptyResponse = gson.toJson(empty);

            if (productname == null && description == null && price == null && quantity == null) {
                try {
                    sendResponse(exchange, 400, emptyResponse);
                } catch (IOException e) {
                    System.out.println(e);
                }
            }

            StringBuilder sql = new StringBuilder("UPDATE products SET ");
            if (productname != null) { sql.append("productname").append(" = ?, "); }
            if (description != null) { sql.append("description").append(" = ?, "); }
            if (price != null) { sql.append("price").append(" = ?, "); }
            if (quantity != null) { sql.append("quantity").append(" = ?, "); }
            sql.setLength(sql.length() - 2);
            sql.append(" WHERE id = ?;");

            String query = sql.toString();
            int count = 1;

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement statement = conn.prepareStatement(query)) {

                if (productname != null) { statement.setString(count, productname); count++; }
                if (description != null) { statement.setString(count, description); count++; }
                if (price != null) { statement.setDouble(count, price); count++; }
                if (quantity != null) { statement.setInt(count, quantity); count++; }

                statement.setInt(count, id);

                int affectedRows = statement.executeUpdate();
                if (affectedRows > 0) {
                    JsonObject item = new JsonObject();
                    item.addProperty("id", id);
                    item.addProperty("name", productname);
                    item.addProperty("description", description);
                    item.addProperty("price", price);
                    item.addProperty("quantity", quantity);
                    String response = gson.toJson(item);
                    try {
                        sendResponse(exchange, 200, response);
                    } catch (IOException e) {
                        System.out.println(e);
                    }
                }
            } catch (SQLException e1) {
                JsonObject item = new JsonObject();
                String response = gson.toJson(item);
                try {
                    sendResponse(exchange, 404, response);
                } catch (IOException e2) {
                    System.out.println(e1);
                    System.out.println(e2);
                }
            }
        }

        private static void delete(HttpExchange exchange, int id, String name, String description, Double price, Integer quantity) {
            String check_sql = "SELECT COUNT(*) FROM products WHERE id = ? AND productname = ? AND description = ? AND price = ? AND quantity = ?";
            String delete_sql = "DELETE FROM products WHERE id = ?";

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement check = conn.prepareStatement(check_sql);
                 PreparedStatement delete = conn.prepareStatement(delete_sql)) {

                check.setInt(1, id);
                check.setString(2, name);
                check.setString(3, description);
                check.setDouble(4, price);
                check.setInt(5, quantity);

                ResultSet result = check.executeQuery();
                if (result.next() && result.getInt(1) > 0) {
                    delete.setInt(1, id);
                    int affectedRows = delete.executeUpdate();
                    if (affectedRows > 0) {
                        JsonObject item = new JsonObject();//empty
                        String response = gson.toJson(item);
                        try {
                            sendResponse(exchange, 200, response);
                        } catch (IOException e) {
                            System.out.println(e);
                        }
                    }
                } else {
                    // don't match
                    JsonObject item = new JsonObject();
                    String response = gson.toJson(item);
                    try {
                        sendResponse(exchange, 404, response);
                    } catch (IOException e) {
                        System.out.println(e);
                    }
                }
            } catch (SQLException e) {
                // invalid_id
                JsonObject item = new JsonObject();
                String response = gson.toJson(item);
                try {
                    sendResponse(exchange, 404, response);
                } catch (IOException e1) {
                    System.out.println(e);
                    System.out.println(e1);
                }
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
                    // invalid type
                    JsonObject empty = new JsonObject();
                    String emptyResponse = gson.toJson(empty);
                    sendResponse(exchange, 404, emptyResponse);
                }

                JsonObject item = new JsonObject();

                String sql = "SELECT id, productname, description, price, quantity FROM products WHERE id = ?";
                try (Connection conn = DriverManager.getConnection(DB_URL);
                     PreparedStatement statement = conn.prepareStatement(sql)) {
                    statement.setInt(1, ID);
                    ResultSet result = statement.executeQuery();
                    if (result.next()) {
                        item.addProperty("id", result.getInt("id"));
                        item.addProperty("name", result.getString("productname"));
                        item.addProperty("description", result.getString("description"));
                        item.addProperty("price", result.getDouble("price"));
                        item.addProperty("quantity", result.getInt("quantity"));
                    } else {
                        // no matching
                        JsonObject empty = new JsonObject();
                        String emptyResponse = gson.toJson(empty);
                        sendResponse(exchange, 404, emptyResponse);
                    }
                } catch (SQLException e) {
                    // no matching
                    JsonObject empty = new JsonObject();
                    String emptyResponse = gson.toJson(empty);
                    sendResponse(exchange, 404, emptyResponse);
                }

                String response = gson.toJson(item);
                sendResponse(exchange, 200, response);
            } else {
                exchange.sendResponseHeaders(405,0);
                exchange.close();
            }
        }
    }

    /*
    private static void sendResponse(HttpExchange exchange, int status, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length());
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes(StandardCharsets.UTF_8));
        os.close();
    }
    */


    private static void sendResponse(HttpExchange exchange, int status, String response) throws IOException {
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}

