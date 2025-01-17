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

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class Main {
    public static void main(String[] args) throws IOException{
        int port = 8081; //TODO: use port from config.json
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.setExecutor(Executors.newFixedThreadPool(20));
        httpServer.createContext("/user", new UserHandler());
        httpServer.setExecutor(null);
        httpServer.start();
        System.out.println("User Service started on port " + port);
    }

    static class UserHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Handle POST requests for /user
            if ("POST".equals(exchange.getRequestMethod())) {
                JSONObject requestJSON = getJSONObject(exchange);

                // Handle "create" command
                if (requestJSON.getString("command").equals("create")) {
                    return;
                }

                // Handle "update" command
                if (requestJSON.getString("command").equals("update")) {
                    return;
                }

                // Handle "delete" command
                if (requestJSON.getString("command").equals("delete")) {
                    return;
                }

                exchange.sendResponseHeaders(405, 0);
                exchange.close();
            }

            // Handle GET requests for /user
            if ("GET".equals(exchange.getRequestMethod())) {

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