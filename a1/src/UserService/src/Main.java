import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.apache.commons.codec.digest.DigestUtils;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;

import java.util.concurrent.Executors;

public class Main {
    private static final String DB_URL = "jdbc:sqlite:./user.sqlite";
    private static Connection connection;
    public static void main(String[] args) throws IOException{
        String configPath = args[0];
        JSONObject config = new JSONObject(new String(Files.readAllBytes(Paths.get(configPath))));
        JSONObject orderConfig = (JSONObject) config.get("UserService");
        int port = orderConfig.getInt("port");
        
        initDatabase();
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.setExecutor(Executors.newFixedThreadPool(20));
        httpServer.createContext("/user", new UserHandler());
        httpServer.start();
        System.out.println("User Service started on port " + port);
    }

    private static void initDatabase() {
        try {
            connection = DriverManager.getConnection(DB_URL);

            String createTableSQL = """
                CREATE TABLE IF NOT EXISTS user (
                    id INTEGER PRIMARY KEY,
                    username VARCHAR(255),
                    email VARCHAR(225),
                    password CHAR(64)
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

    static class UserHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Handle POST requests for /user
            if ("POST".equals(exchange.getRequestMethod())) {
                JSONObject requestJSON = getJSONObject(exchange);

                if (requestJSON == null || !requestJSON.has("command")) {
                    exchange.sendResponseHeaders(400, -1);
                    exchange.close();
                    return;
                }

                if (!(requestJSON.get("command") instanceof String)) {
                    exchange.sendResponseHeaders(400, -1);
                    exchange.close();
                    return;
                }

                // Handle "create" command
                if (requestJSON.getString("command").equals("create")) {

                    if (!validRequest(requestJSON)) {
                        exchange.sendResponseHeaders(400, -1);
                        exchange.close();
                        return;
                    }

                    if (checkIdExist(requestJSON.getInt("id"))) {
                        exchange.sendResponseHeaders(409, -1);
                        exchange.close();
                        return;
                    }

                    int id = requestJSON.getInt("id");

                    String username = requestJSON.getString("username");
                    String email = requestJSON.getString("email");
                    String password = requestJSON.getString("password");
                    String hashedPassword = DigestUtils.sha256Hex(password).toUpperCase();

                    String insertSQL = String.format(
                        "INSERT INTO user (id, username, email, password) VALUES (%d, '%s', '%s', '%s');",
                        id, username, email, hashedPassword
                    );

                    try (Statement statement = connection.createStatement()) {
                        statement.execute(insertSQL);
                    } catch (SQLException e) {
                        e.printStackTrace();
                        exchange.sendResponseHeaders(500, -1);
                        exchange.close();
                        return;
                    }

                    returnJson(exchange, id);
                }

                // Handle "update" command
                if (requestJSON.getString("command").equals("update")) {

                    if (!requestJSON.has("id")) {
                        exchange.sendResponseHeaders(400, -1);
                        exchange.close();
                        return;
                    }

                    if (!(requestJSON.get("id") instanceof Integer)) {
                        exchange.sendResponseHeaders(400, -1);
                        exchange.close();
                        return;
                    }

                    int id = requestJSON.getInt("id");
                    StringBuilder updateSQL = new StringBuilder("UPDATE user SET ");
                    boolean first = true;

                    if (requestJSON.has("username")) {
                        if (!(requestJSON.get("username") instanceof String)) {
                            exchange.sendResponseHeaders(400, -1);
                            exchange.close();
                            return;
                        }
                        if (requestJSON.getString("username").isEmpty()) {
                            exchange.sendResponseHeaders(400, -1);
                            exchange.close();
                            return;
                        }
                        String username = requestJSON.getString("username");
                        updateSQL.append("username = '").append(username).append("'");
                        first = false;
                    }

                    if (requestJSON.has("email")) {
                        if (!(requestJSON.get("email") instanceof String)) {
                            exchange.sendResponseHeaders(400, -1);
                            exchange.close();
                            return;
                        }
                        if (requestJSON.getString("email").isEmpty()) {
                            exchange.sendResponseHeaders(400, -1);
                            exchange.close();
                            return;
                        }
                        String email = requestJSON.getString("email");
                        if (!first) updateSQL.append(", ");
                        updateSQL.append("email = '").append(email).append("'");
                        first = false;
                    }

                    if (requestJSON.has("password")) {
                        if (!(requestJSON.get("password") instanceof String)) {
                            exchange.sendResponseHeaders(400, -1);
                            exchange.close();
                            return;
                        }
                        if (requestJSON.getString("password").isEmpty()) {
                            exchange.sendResponseHeaders(400, -1);
                            exchange.close();
                            return;
                        }
                        String password = requestJSON.getString("password");
                        String hashedPassword = DigestUtils.sha256Hex(password).toUpperCase();
                        requestJSON.put("password", hashedPassword);
                        requestJSON.remove("command");
                        if (!first) updateSQL.append(", ");
                        updateSQL.append("password = '").append(hashedPassword).append("'");
                    }

                    if (!first) {
                        updateSQL.append(" WHERE id = ").append(id).append(";");

                        try (Statement statement = connection.createStatement()) {
                            statement.execute(updateSQL.toString());
                        } catch (SQLException e) {
                            e.printStackTrace();
                            exchange.sendResponseHeaders(500, -1);
                            exchange.close();
                            return;
                        }
                    }

                    returnJson(exchange, id);
                    return;
                }

                // Handle "delete" command
                if (requestJSON.getString("command").equals("delete")) {

                    if (!validRequest(requestJSON)) {
                        exchange.sendResponseHeaders(400, -1);
                        exchange.close();
                        return;
                    }

                    int id = requestJSON.getInt("id");
                    String username = requestJSON.getString("username");
                    String email = requestJSON.getString("email");
                    String password = requestJSON.getString("password");

                    String hashedPassword = DigestUtils.sha256Hex(password);

                    String checkSQL = String.format(
                        "SELECT * FROM user WHERE id = %d AND username = '%s' AND email = '%s' AND password = '%s';",
                        id, username, email, hashedPassword
                    );

                    try (Statement statement = connection.createStatement()) {
                        ResultSet resultSet = statement.executeQuery(checkSQL);
                        if (!resultSet.next()) {
                            exchange.sendResponseHeaders(404, -1);
                            exchange.close();
                            return;
                        }
                    } catch (SQLException e) {
                        e.printStackTrace();
                        exchange.sendResponseHeaders(500, -1);
                        exchange.close();
                        return;
                    }

                    String deleteSQL = String.format(
                        "DELETE FROM user WHERE id = %d AND username = '%s' AND email = '%s' AND password = '%s';",
                        id, username, email, hashedPassword
                    );

                    try (Statement statement = connection.createStatement()) {
                        statement.execute(deleteSQL);
                    } catch (SQLException e) {
                        e.printStackTrace();
                        exchange.sendResponseHeaders(500, -1);
                        exchange.close();
                        return;
                    }

                    exchange.sendResponseHeaders(200, 0);
                    exchange.close();
                    return;
                }

                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            }

            // Handle GET requests for /user
            if ("GET".equals(exchange.getRequestMethod())) {
                String path = exchange.getRequestURI().getPath();
                String[] segments = path.split("/");
                if (segments.length < 3) {
                    exchange.sendResponseHeaders(400, -1);
                    exchange.close();
                    return;
                }

                String id = segments[2];
                if (!id.matches("\\d+")) {
                    exchange.sendResponseHeaders(400, -1);
                    exchange.close();
                    return;
                }
                returnJson(exchange, Integer.parseInt(id));
            }
        }

        private static void returnJson(HttpExchange exchange, int id) throws IOException {
            JSONObject responseJSON = getJsonByID(id);

            if (responseJSON == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseJSON.toString().getBytes());
            }
            exchange.close();
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
            String requestBody = getRequestBody(exchange);
            try {
                return new JSONObject(requestBody);
            } catch (JSONException e) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return null;
            }
        }

        private static boolean validRequest(JSONObject requestJSON) {
            if (!requestJSON.has("id") || !requestJSON.has("username") || !requestJSON.has("email") || !requestJSON.has("password")) {
                return false;
            }

            if (!(requestJSON.get("id") instanceof Integer) || !(requestJSON.get("username") instanceof String) || !(requestJSON.get("email") instanceof String) || !(requestJSON.get("password") instanceof String)) {
                return false;
            }

            if (requestJSON.getString("username").isEmpty() || requestJSON.getString("email").isEmpty() || requestJSON.getString("password").isEmpty()) {
                return false;
            }
            return true;
        }

        private static boolean checkIdExist(int id) {
            String selectSQL = String.format("SELECT * FROM user WHERE id = %d;", id);
            try (Statement statement = connection.createStatement()) {
                ResultSet resultSet = statement.executeQuery(selectSQL);
                if (!resultSet.next()) {
                    return false;
                }
            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
            return true;
        }

        private static JSONObject getJsonByID(int id) {
            String selectSQL = String.format("SELECT * FROM user WHERE id = %d;", id);
            try (Statement statement = connection.createStatement()) {
                ResultSet resultSet = statement.executeQuery(selectSQL);
                if (!resultSet.next()) {
                    return null;
                }
                JSONObject responseJSON = new JSONObject();
                responseJSON.put("id", resultSet.getInt("id"));
                responseJSON.put("username", resultSet.getString("username"));
                responseJSON.put("email", resultSet.getString("email"));
                responseJSON.put("password", resultSet.getString("password"));
                return responseJSON;
            } catch (SQLException e) {
                e.printStackTrace();
                return null;
            }
        }
    }
}