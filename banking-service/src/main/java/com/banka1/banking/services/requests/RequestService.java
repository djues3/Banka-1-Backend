package com.banka1.banking.services.requests;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Slf4j
@Service
public class RequestService {

    private final HttpClient client = HttpClient.newHttpClient();

    public HttpResponse<String> send(RequestBuilder builder) throws Exception {
        HttpRequest request = builder.build();
        log.info("Sending request to trading service: {}", request);
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
