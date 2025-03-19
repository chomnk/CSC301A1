import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    // List of backend server URLs.
    private static final List<String> BACKEND_SERVERS = Arrays.asList(
            "http://127.0.0.1:14000",
            "http://127.0.0.1:14010",
            "http://127.0.0.1:14020",
            "http://127.0.0.1:14030",
            "http://127.0.0.1:14040",
            "http://127.0.0.1:14050"
    );

    // Atomic counter for round-robin selection.
    private static final AtomicInteger counter = new AtomicInteger(0);

    // Create an asynchronous HttpClient with a custom executor and timeout.
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newFixedThreadPool(100))
            .build();

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage: java AsyncLoadBalancer <ip> <port>");
            System.exit(1);
        }
        String ip = args[0];
        int port = Integer.parseInt(args[1]);

        // Increase backlog to support high concurrency.
        HttpServer server = HttpServer.create(new InetSocketAddress(ip, port), 1024);
        server.createContext("/", new AsyncLoadBalancerHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("Async load balancer started on " + ip + ":" + port);
    }

    static class AsyncLoadBalancerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            // Select a backend server using round-robin.
            int index = counter.getAndIncrement() % BACKEND_SERVERS.size();
            String backend = BACKEND_SERVERS.get(index);

            // Construct the full backend URI.
            String requestURI = exchange.getRequestURI().toString();
            URI backendURI;
            try {
                backendURI = new URI(backend + requestURI);
            } catch (URISyntaxException e) {
                e.printStackTrace();
                try {
                    exchange.sendResponseHeaders(500, -1);
                } catch (IOException ioException) {
                    ioException.printStackTrace();
                }
                exchange.close();
                return;
            }

            // Build the HttpRequest for the backend with a per-request timeout.
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(backendURI)
                    .timeout(Duration.ofSeconds(10))
                    .method(exchange.getRequestMethod(), getBodyPublisher(exchange));

            // Copy incoming headers, excluding ones that may interfere.
            exchange.getRequestHeaders().forEach((key, values) -> {
                if ("Content-Length".equalsIgnoreCase(key) ||
                        "Host".equalsIgnoreCase(key) ||
                        "Connection".equalsIgnoreCase(key) ||
                        "Expect".equalsIgnoreCase(key)) {
                    return; // Skip these headers.
                }
                String headerValue = String.join(",", values);
                requestBuilder.header(key, headerValue);
            });

            HttpRequest backendRequest = requestBuilder.build();

            // Asynchronously send the request to the backend.
            CompletableFuture<HttpResponse<byte[]>> responseFuture =
                    httpClient.sendAsync(backendRequest, HttpResponse.BodyHandlers.ofByteArray());

            responseFuture.whenComplete((response, error) -> {
                if (error != null) {
                    error.printStackTrace();
                    try {
                        exchange.sendResponseHeaders(502, -1); // 502 Bad Gateway.
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    try {
                        // Forward backend response headers, but exclude "Transfer-Encoding"
                        response.headers().map().forEach((key, values) -> {
                            if (!"transfer-encoding".equalsIgnoreCase(key)) {
                                exchange.getResponseHeaders().put(key, values);
                            }
                        });
                        int statusCode = response.statusCode();
                        byte[] responseBody = response.body();
                        exchange.sendResponseHeaders(statusCode, responseBody.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(responseBody);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                exchange.close();
            });
        }

        // Utility to create a BodyPublisher from the HttpExchange request.
        private HttpRequest.BodyPublisher getBodyPublisher(HttpExchange exchange) {
            String method = exchange.getRequestMethod();
            if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
                try (InputStream is = exchange.getRequestBody()) {
                    byte[] body = is.readAllBytes();
                    return HttpRequest.BodyPublishers.ofByteArray(body);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return HttpRequest.BodyPublishers.noBody();
        }
    }
}
