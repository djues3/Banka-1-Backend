package com.banka1.banking.services.requests;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.Map;

public class RequestBuilder {

    private String url = "";
    private String method = "GET";
    private Map<String, String> headers = new HashMap<>();
    private String body = "";

    public RequestBuilder url(String url) {
        this.url = url;
        return this;
    }

    public RequestBuilder method(String method) {
        this.method = method;
        return this;
    }

    public RequestBuilder headers(Map<String, String> headers) {
        this.headers = headers;
        return this;
    }

    public RequestBuilder addHeader(String key, String value) {
        this.headers.put(key, value);
        return this;
    }

    public RequestBuilder body(String body) {
        this.body = body;
        return this;
    }

    public HttpRequest build() {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url));

        headers.forEach(builder::header);

        return switch (method.toUpperCase()) {
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
            case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build();
            case "DELETE" -> builder.method("DELETE", HttpRequest.BodyPublishers.ofString(body)).build();
            default -> builder.GET().build();
        };
    }
}
