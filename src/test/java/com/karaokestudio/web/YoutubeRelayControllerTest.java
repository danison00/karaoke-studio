package com.karaokestudio.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.karaokestudio.youtube.UpstreamStreamClient;
import com.karaokestudio.youtube.YoutubeStream;
import com.karaokestudio.youtube.preparation.YoutubeRelayRegistry;

class YoutubeRelayControllerTest {

    @Test
    void streamsRelayResponseOnlyToLoopbackRequest() throws Exception {
        var upstream = new FakeUpstream();
        var registry = new YoutubeRelayRegistry(upstream, URI.create("http://127.0.0.1:8080"), java.time.Duration.ofMinutes(2));
        var video = stream(URI.create("https://r1---sn.googlevideo.com/videoplayback?sig=secret"));
        var handles = registry.register(UUID.randomUUID(), 0, video, video);
        upstream.next = new UpstreamStreamClient.UpstreamResponse(
                200, "video/mp4", 4, null, true, new ByteArrayInputStream("data".getBytes()), () -> { });
        var mockMvc = MockMvcBuilders.standaloneSetup(new YoutubeRelayController(registry)).build();

        var async = mockMvc.perform(get(handles.video().getPath()).with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                }))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(async))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("data");
        assertThat(result.getResponse().getHeader("Content-Type")).isEqualTo("video/mp4");
    }

    @Test
    void rejectsNonLoopbackRequest() throws Exception {
        var registry = new YoutubeRelayRegistry(new FakeUpstream(),
                URI.create("http://127.0.0.1:8080"), java.time.Duration.ofMinutes(2));
        var video = stream(URI.create("https://r1---sn.googlevideo.com/videoplayback?sig=secret"));
        var handles = registry.register(UUID.randomUUID(), 0, video, video);
        var mockMvc = MockMvcBuilders.standaloneSetup(new YoutubeRelayController(registry))
                .setControllerAdvice(new ApiExceptionHandler()).build();

        mockMvc.perform(get(handles.video().getPath()).with(request -> {
                    request.setRemoteAddr("10.0.0.2");
                    return request;
                }))
                .andExpect(status().isBadRequest());
    }

    private static YoutubeStream stream(URI uri) {
        return new YoutubeStream() {
            @Override public URI upstreamUri() { return uri; }
            @Override public String contentType() { return "video/mp4"; }
            @Override public long contentLength() { return 4; }
            @Override public long durationMs() { return 10_000; }
            @Override public boolean progressive() { return false; }
            @Override public boolean sabr() { return false; }
            @Override public boolean otf() { return false; }
            @Override public void writeTo(java.io.OutputStream output) { }
        };
    }

    private static final class FakeUpstream implements com.karaokestudio.youtube.UpstreamStreamClient {
        private UpstreamStreamClient.UpstreamResponse next;

        @Override
        public UpstreamStreamClient.UpstreamResponse open(URI uri, String rangeHeader) {
            return next;
        }
    }
}
