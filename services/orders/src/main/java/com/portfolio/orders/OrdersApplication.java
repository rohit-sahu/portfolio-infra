package com.portfolio.orders;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class OrdersApplication {
    private OrdersApplication() {}

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/orders", OrdersApplication::handleRequest);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        server.start();
        System.out.println("Orders placeholder listening on port " + port);
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        boolean healthCheck = "/orders/health".equals(exchange.getRequestURI().getPath())
                && "GET".equals(exchange.getRequestMethod());
        int status = healthCheck ? 200 : 501;
        String response = healthCheck ? "ok\n" : "Order operations are not implemented yet.\n";
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
