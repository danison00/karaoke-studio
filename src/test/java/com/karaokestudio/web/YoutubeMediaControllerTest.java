package com.karaokestudio.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.karaokestudio.youtube.YoutubeMediaService;
import com.karaokestudio.youtube.YoutubeUrlException;
import com.karaokestudio.youtube.ffmpeg.FfmpegUnavailableException;
import com.karaokestudio.youtube.preparation.MediaNotReadyException;
import com.karaokestudio.youtube.preparation.PreparedMedia;
import com.karaokestudio.youtube.preparation.PreparationMode;
import com.karaokestudio.youtube.preparation.PreparationStatus;
import com.karaokestudio.youtube.preparation.YoutubePreparationService;
import com.karaokestudio.youtube.preparation.YoutubePreparationSnapshot;

class YoutubeMediaControllerTest {

    private static final String URL = "https://www.youtube.com/watch?v=abcdefghijk";

    private YoutubePreparationService preparationService;
    private YoutubeMediaService mediaService;
    private CachedMediaStreamer cachedMediaStreamer;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        preparationService = mock(YoutubePreparationService.class);
        mediaService = mock(YoutubeMediaService.class);
        cachedMediaStreamer = mock(CachedMediaStreamer.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new YoutubeMediaController(preparationService, mediaService, cachedMediaStreamer))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsAcceptedAndLocationWhilePreparationIsRunning() throws Exception {
        var id = UUID.randomUUID();
        when(preparationService.start(eq(URL), any())).thenReturn(snapshot(id, PreparationStatus.PREPARING, PreparationMode.MUXED));

        mockMvc.perform(post("/api/youtube/preparations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + URL + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/youtube/preparations/" + id))
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("PREPARING")
                        .doesNotContain("googlevideo")
                        .doesNotContain("target\\"));
    }

    @Test
    void returnsOkWhenPreparationIsAlreadyReady() throws Exception {
        var id = UUID.randomUUID();
        when(preparationService.start(eq(URL), any())).thenReturn(snapshot(id, PreparationStatus.READY, PreparationMode.PROGRESSIVE));

        mockMvc.perform(post("/api/youtube/preparations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + URL + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Location", "/api/youtube/preparations/" + id))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("READY")
                        .contains("/api/youtube/preparations/" + id + "/media"));
    }

    @Test
    void exposesPreparationStatusWithoutSignedUrlsOrFilesystemPaths() throws Exception {
        var id = UUID.randomUUID();
        when(preparationService.get(id)).thenReturn(snapshot(id, PreparationStatus.READY, PreparationMode.MUXED));

        var result = mockMvc.perform(get("/api/youtube/preparations/" + id))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("MUXED")
                .contains("mediaUrl")
                .doesNotContain("googlevideo")
                .doesNotContain("target\\")
                .doesNotContain("C:/");
    }

    @Test
    void mapsInvalidUrlToBadRequest() throws Exception {
        when(preparationService.start(eq(URL), any())).thenThrow(new YoutubeUrlException("URL inválida"));

        mockMvc.perform(post("/api/youtube/preparations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + URL + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mapsMissingPreparationToNotFound() throws Exception {
        var id = UUID.randomUUID();
        when(preparationService.get(id)).thenThrow(new com.karaokestudio.youtube.preparation.MediaPreparationException("expirada"));

        mockMvc.perform(get("/api/youtube/preparations/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void mapsFfmpegUnavailableToServiceUnavailable() throws Exception {
        when(preparationService.start(eq(URL), any())).thenThrow(new FfmpegUnavailableException("FFmpeg ausente"));

        mockMvc.perform(post("/api/youtube/preparations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + URL + "\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void streamsMuxedMediaWithRangeAndReleasesItAfterAsyncResponse() throws Exception {
        var id = UUID.randomUUID();
        var prepared = mock(PreparedMedia.class);
        var media = new YoutubeMediaService.MediaStream(
                206,
                "video/mp4",
                4,
                "bytes 2-5/10",
                true,
                PreparationMode.MUXED,
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)),
                () -> { },
                "\"10-1\"");
        when(preparationService.open(id)).thenReturn(prepared);
        when(prepared.mode()).thenReturn(PreparationMode.MUXED);
        when(cachedMediaStreamer.open(prepared, "bytes=2-5")).thenReturn(media);

        var async = mockMvc.perform(get("/api/youtube/preparations/" + id + "/media")
                        .header("Range", "bytes=2-5"))
                .andExpect(request().asyncStarted())
                .andReturn();
        var result = mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Content-Length", "4"))
                .andExpect(header().string("Content-Range", "bytes 2-5/10"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("ETag", "\"10-1\""))
                .andExpect(header().string("X-Karaoke-Media-Mode", "MUXED"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("data");
        verify(cachedMediaStreamer).open(prepared, "bytes=2-5");
    }

    @Test
    void mapsMediaNotReadyToConflict() throws Exception {
        var id = UUID.randomUUID();
        when(preparationService.open(id)).thenThrow(new MediaNotReadyException("ainda preparando"));

        mockMvc.perform(get("/api/youtube/preparations/" + id + "/media"))
                .andExpect(status().isConflict());
    }

    private static YoutubePreparationSnapshot snapshot(UUID id, PreparationStatus status, PreparationMode mode) {
        return new YoutubePreparationSnapshot(
                id,
                "abcdefghijk",
                "Título",
                status,
                mode,
                "video/mp4",
                65_000,
                status == PreparationStatus.READY ? 123_000L : null,
                status == PreparationStatus.READY ? "/api/youtube/preparations/" + id + "/media" : null,
                null);
    }
}
