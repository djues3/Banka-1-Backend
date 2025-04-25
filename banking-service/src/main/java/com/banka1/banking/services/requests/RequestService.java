package com.banka1.banking.services.requests;

import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Service
public class RequestService {

    private final HttpClient client = HttpClient.newHttpClient();

    public HttpResponse<String> send(RequestBuilder builder) throws Exception {
        HttpRequest request = builder.build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
