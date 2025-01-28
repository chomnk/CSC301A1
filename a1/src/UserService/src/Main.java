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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class Main {
    private static final String DB_URL = "jdbc:sqlite:/workspaces/CSC301A1/a1/user.sqlite";
    private static Connection connection;
    public static void main(String[] args) throws IOException{
        String configPath = args[0];
        System.out.println(configPath);
        JSONObject config = new JSONObject(new String(Files.readAllBytes(Paths.get(configPath))));
        JSONObject orderConfig = (JSONObject) config.get("UserService");
        int port = orderConfig.getInt("port");
        
        initDatabase();
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.setExecutor(Executors.newFixedThreadPool(20));
        httpServer.createContext("/user", new UserHandler());
        httpServer.setExecutor(null);
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
                    password VARCHAR(225)
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

                // Handle "create" command
                if (requestJSON.getString("command").equals("create")) {
                    int id = requestJSON.getInt("id");
                    String username = requestJSON.getString("username");
                    String email = requestJSON.getString("email");
                    String password = requestJSON.getString("password");
                    if (username == null || email == null || password == null) {
                        exchange.sendResponseHeaders(400, 0);
                        exchange.close();
                        return;
                    }

                    String insertSQL = String.format(
                        "INSERT INTO user (id, username, email, password) VALUES (%d, '%s', '%s', '%s');",
                        id, username, email, password
                    );

                    try (Statement statement = connection.createStatement()) {
                        statement.execute(insertSQL);
                    } catch (SQLException e) {
                        e.printStackTrace();
                        exchange.sendResponseHeaders(500, 0);
                        exchange.close();
                        return;
                    }

                    exchange.sendResponseHeaders(200, 0);
                    exchange.close();
                    return;
                }

                // Handle "update" command
                if (requestJSON.getString("command").equals("update")) {
                    int id = requestJSON.getInt("id");
                    StringBuilder updateSQL = new StringBuilder("UPDATE user SET ");
                    boolean first = true;

                    if (requestJSON.has("username")) {
                        String username = requestJSON.getString("username");
                        if (!first) updateSQL.append(", ");
                        updateSQL.append("username = '").append(username).append("'");
                        first = false;
                    }

                    if (requestJSON.has("email")) {
                        String email = requestJSON.getString("email");
                        if (!first) updateSQL.append(", ");
                        updateSQL.append("email = '").append(email).append("'");
                        first = false;
                    }

                    if (requestJSON.has("password")) {
                        String password = requestJSON.getString("password");
                        if (!first) updateSQL.append(", ");
                        updateSQL.append("password = '").append(password).append("'");
                    }

                    updateSQL.append(" WHERE id = ").append(id).append(";");

                    try (Statement statement = connection.createStatement()) {
                        statement.execute(updateSQL.toString());
                    } catch (SQLException e) {
                        e.printStackTrace();
                        exchange.sendResponseHeaders(500, 0);
                        exchange.close();
                        return;
                    }

                    exchange.sendResponseHeaders(200, 0);
                    exchange.close();
                    return;
                }

                // Handle "delete" command
                if (requestJSON.getString("command").equals("delete")) {
                    int id = requestJSON.getInt("id");
                    String username = requestJSON.getString("username");
                    String email = requestJSON.getString("email");
                    String password = requestJSON.getString("password");

                    if (username == null || email == null || password == null) {
                        exchange.sendResponseHeaders(400, 0);
                        exchange.close();
                        return;
                    }

                    String deleteSQL = String.format(
                        "DELETE FROM user WHERE id = %d AND username = '%s' AND email = '%s' AND password = '%s';",
                        id, username, email, password
                    );

                    try (Statement statement = connection.createStatement()) {
                        statement.execute(deleteSQL);
                    } catch (SQLException e) {
                        e.printStackTrace();
                        exchange.sendResponseHeaders(500, 0);
                        exchange.close();
                        return;
                    }

                    exchange.sendResponseHeaders(200, 0);
                    exchange.close();
                    return;
                }

                exchange.sendResponseHeaders(405, 0);
                exchange.close();
            }

            // Handle GET requests for /user
            if ("GET".equals(exchange.getRequestMethod())) {
                String path = exchange.getRequestURI().getPath();
                String[] segments = path.split("/");
                if (segments.length < 3) {
                    exchange.sendResponseHeaders(400, 0);
                    exchange.close();
                    return;
                }

                String id = segments[2];
                String selectSQL = String.format("SELECT * FROM user WHERE id = %s;", id);

                try (Statement statement = connection.createStatement()) {

                    ResultSet resultSet = statement.executeQuery(selectSQL);

                    if (!resultSet.next()) {
                        exchange.sendResponseHeaders(404, 0);
                        exchange.close();
                        return;
                    }

                    JSONObject jsonResponse = new JSONObject();
                    jsonResponse.put("id", resultSet.getInt("id"));
                    jsonResponse.put("username", resultSet.getString("username"));
                    jsonResponse.put("email", resultSet.getString("email"));

                    String response = jsonResponse.toString();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }

                } catch (SQLException e) {
                    e.printStackTrace();
                    exchange.sendResponseHeaders(500, 0);
                    exchange.close();
                    return;
                }
            }
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