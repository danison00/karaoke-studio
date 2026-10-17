package com.karaokestudio.web;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.net.URI;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.karaokestudio.youtube.HttpUpstreamStreamClient;
import com.karaokestudio.youtube.JavaTubeResolver;
import com.karaokestudio.youtube.UpstreamHostPolicy;
import com.karaokestudio.youtube.UpstreamStreamClient;
import com.karaokestudio.youtube.YoutubeMediaProperties;
import com.karaokestudio.youtube.YoutubeUrlValidator;
import com.karaokestudio.youtube.ffmpeg.CommandExecutor;
import com.karaokestudio.youtube.ffmpeg.FfmpegCapabilities;
import com.karaokestudio.youtube.ffmpeg.FfmpegProbe;
import com.karaokestudio.youtube.ffmpeg.FfmpegRunner;
import com.karaokestudio.youtube.ffmpeg.ProcessCommandExecutor;
import com.karaokestudio.youtube.ffmpeg.ProcessFfmpegRunner;
import com.karaokestudio.youtube.preparation.SecureStreamDownloader;
import com.karaokestudio.youtube.preparation.HlsMediaCache;
import com.karaokestudio.youtube.preparation.HlsPlaybackSessionService;
import com.karaokestudio.youtube.preparation.HlsPlaylistReader;
import com.karaokestudio.youtube.preparation.YoutubeRelayRegistry;
import com.karaokestudio.youtube.preparation.YoutubeMediaCache;
import com.karaokestudio.youtube.preparation.YoutubePreparationService;

@Configuration
@EnableConfigurationProperties(YoutubeMediaProperties.class)
public class YoutubeMediaConfiguration {

    @Bean
    YoutubeUrlValidator youtubeUrlValidator(YoutubeMediaProperties properties) {
        return new YoutubeUrlValidator(properties.getMaxUrlLength());
    }

    @Bean
    JavaTubeResolver javaTubeResolver(YoutubeMediaProperties properties) {
        return new JavaTubeResolver(properties.getMaxHeight());
    }

    @Bean
    UpstreamHostPolicy upstreamHostPolicy() {
        return new UpstreamHostPolicy();
    }

    @Bean
    RequestLimiter requestLimiter(YoutubeMediaProperties properties) {
        return new RequestLimiter(
                properties.getMaxGlobalStreams(),
                properties.getMaxStreamsPerIp(),
                properties.getMaxResolutionsPerMinute());
    }

    @Bean
    UpstreamStreamClient upstreamStreamClient(
            UpstreamHostPolicy hostPolicy,
            YoutubeMediaProperties properties) {
        return new HttpUpstreamStreamClient(
                hostPolicy,
                properties.getUpstreamConnectTimeout(),
                properties.getUpstreamReadTimeout());
    }

    @Bean
    SecureStreamDownloader secureStreamDownloader(UpstreamStreamClient upstreamClient) {
        return new SecureStreamDownloader(upstreamClient);
    }

    @Bean
    YoutubeMediaCache youtubeMediaCache(YoutubeMediaProperties properties) {
        var cache = new YoutubeMediaCache(
                properties.getCacheDir(),
                properties.getCacheSize().toBytes(),
                properties.getReadyTtl(),
                properties.getFailedTtl(),
                java.time.Clock.systemUTC());
        cache.cleanupAbandoned();
        return cache;
    }

    @Bean
    HlsMediaCache hlsMediaCache(YoutubeMediaProperties properties) {
        return new HlsMediaCache(properties.getCacheDir().resolve("hls"));
    }

    @Bean
    HlsPlaylistReader hlsPlaylistReader() {
        return new HlsPlaylistReader();
    }

    @Bean
    YoutubeRelayRegistry youtubeRelayRegistry(
            UpstreamStreamClient upstreamClient,
            @Value("${server.port:8080}") int serverPort) {
        return new YoutubeRelayRegistry(
                upstreamClient,
                URI.create("http://127.0.0.1:" + serverPort),
                java.time.Duration.ofMinutes(10));
    }

    @Bean
    CommandExecutor commandExecutor() {
        return new ProcessCommandExecutor();
    }

    @Bean
    FfmpegCapabilities ffmpegCapabilities(YoutubeMediaProperties properties, CommandExecutor executor) {
        return new FfmpegProbe(properties.getFfmpegPath(), executor).probe();
    }

    @Bean
    FfmpegRunner ffmpegRunner(YoutubeMediaProperties properties, CommandExecutor executor) {
        return new ProcessFfmpegRunner(properties.getFfmpegPath(), executor);
    }

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService preparationExecutor(YoutubeMediaProperties properties) {
        var queue = properties.getMaxQueue() == 0
                ? new java.util.concurrent.SynchronousQueue<Runnable>()
                : new ArrayBlockingQueue<Runnable>(properties.getMaxQueue());
        return new ThreadPoolExecutor(
                properties.getActiveWorkers(),
                properties.getActiveWorkers(),
                0L,
                TimeUnit.MILLISECONDS,
                queue,
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    YoutubePreparationService youtubePreparationService(
            YoutubeUrlValidator urlValidator,
            com.karaokestudio.youtube.JavaTubeResolver resolver,
            RequestLimiter requestLimiter,
            SecureStreamDownloader downloader,
            YoutubeMediaCache cache,
            FfmpegRunner ffmpegRunner,
            FfmpegCapabilities ffmpegCapabilities,
            ExecutorService preparationExecutor,
            YoutubeMediaProperties properties) {
        return new YoutubePreparationService(
                urlValidator,
                resolver,
                requestLimiter,
                downloader,
                cache,
                ffmpegRunner,
                ffmpegCapabilities,
                preparationExecutor,
                properties.getActiveWorkers(),
                properties.getMaxQueue(),
                properties.getPreparationTimeout(),
                properties.getMaxInputSize().toBytes(),
                properties.getMaxDuration().toMillis());
    }

    @Bean(destroyMethod = "close")
    HlsPlaybackSessionService hlsPlaybackSessionService(
            YoutubeUrlValidator urlValidator,
            JavaTubeResolver resolver,
            RequestLimiter requestLimiter,
            FfmpegRunner ffmpegRunner,
            FfmpegCapabilities ffmpegCapabilities,
            ExecutorService preparationExecutor,
            HlsMediaCache hlsMediaCache,
            HlsPlaylistReader hlsPlaylistReader,
            YoutubeRelayRegistry youtubeRelayRegistry,
            com.karaokestudio.youtube.YoutubeMediaService youtubeMediaService,
            YoutubeMediaProperties properties) {
        return new HlsPlaybackSessionService(
                urlValidator,
                resolver,
                requestLimiter,
                ffmpegRunner,
                ffmpegCapabilities,
                preparationExecutor,
                hlsMediaCache,
                hlsPlaylistReader,
                youtubeRelayRegistry,
                youtubeMediaService,
                properties.getPreparationTimeout(),
                properties.getMaxInputSize().toBytes(),
                properties.getMaxDuration().toMillis());
    }
}
