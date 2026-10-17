package com.karaokestudio;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaticApplicationSmokeTest {

    @LocalServerPort
    private int port;

    @Test
    void servesAngularShellAndAudioWorklet() throws Exception {
        var client = HttpClient.newHttpClient();
        var shell = get(client, "/");
        var worklet = get(client, "/audio-worklets/soundtouch-processor.js");

        assertThat(shell.statusCode()).isEqualTo(200);
        assertThat(shell.headers().firstValue("Content-Type")).hasValueSatisfying(value -> assertThat(value).contains("text/html"));
        assertThat(shell.body()).contains("<app-root>");
        assertThat(shell.body()).contains("type=\"module\"");
        assertThat(worklet.statusCode()).isEqualTo(200);
        assertThat(worklet.body()).isNotBlank();
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
