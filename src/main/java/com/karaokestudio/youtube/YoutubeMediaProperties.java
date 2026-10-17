package com.karaokestudio.youtube;

import java.nio.file.Path;
import java.time.Duration;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "app.youtube")
public class YoutubeMediaProperties {

    private int maxUrlLength = 2048;
    private Duration resolutionTimeout = Duration.ofSeconds(20);
    private Duration upstreamConnectTimeout = Duration.ofSeconds(10);
    private Duration upstreamReadTimeout = Duration.ofSeconds(30);
    private int maxGlobalStreams = 8;
    private int maxStreamsPerIp = 2;
    private int maxResolutionsPerMinute = 10;
    private int maxHeight = 360;
    private Duration maxDuration = Duration.ofHours(2);
    private DataSize maxInputSize = DataSize.ofGigabytes(2);
    private Duration preparationTimeout = Duration.ofMinutes(15);
    private Duration readyTtl = Duration.ofHours(2);
    private Duration failedTtl = Duration.ofMinutes(10);
    private DataSize cacheSize = DataSize.ofGigabytes(5);
    private int activeWorkers = 2;
    private int maxQueue = 8;
    private Duration cleanupInterval = Duration.ofMinutes(5);
    private String ffmpegPath = "ffmpeg";
    private Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "karaoke-studio-youtube");

    public int getMaxUrlLength() { return maxUrlLength; }
    public void setMaxUrlLength(int value) { maxUrlLength = value; }
    public Duration getResolutionTimeout() { return resolutionTimeout; }
    public void setResolutionTimeout(Duration value) { resolutionTimeout = value; }
    public Duration getUpstreamConnectTimeout() { return upstreamConnectTimeout; }
    public void setUpstreamConnectTimeout(Duration value) { upstreamConnectTimeout = value; }
    public Duration getUpstreamReadTimeout() { return upstreamReadTimeout; }
    public void setUpstreamReadTimeout(Duration value) { upstreamReadTimeout = value; }
    public int getMaxGlobalStreams() { return maxGlobalStreams; }
    public void setMaxGlobalStreams(int value) { maxGlobalStreams = value; }
    public int getMaxStreamsPerIp() { return maxStreamsPerIp; }
    public void setMaxStreamsPerIp(int value) { maxStreamsPerIp = value; }
    public int getMaxResolutionsPerMinute() { return maxResolutionsPerMinute; }
    public void setMaxResolutionsPerMinute(int value) { maxResolutionsPerMinute = value; }
    public int getMaxHeight() { return maxHeight; }
    public void setMaxHeight(int value) { maxHeight = value; }
    public Duration getMaxDuration() { return maxDuration; }
    public void setMaxDuration(Duration value) { maxDuration = value; }
    public DataSize getMaxInputSize() { return maxInputSize; }
    public void setMaxInputSize(DataSize value) { maxInputSize = value; }
    public Duration getPreparationTimeout() { return preparationTimeout; }
    public void setPreparationTimeout(Duration value) { preparationTimeout = value; }
    public Duration getReadyTtl() { return readyTtl; }
    public void setReadyTtl(Duration value) { readyTtl = value; }
    public Duration getFailedTtl() { return failedTtl; }
    public void setFailedTtl(Duration value) { failedTtl = value; }
    public DataSize getCacheSize() { return cacheSize; }
    public void setCacheSize(DataSize value) { cacheSize = value; }
    public int getActiveWorkers() { return activeWorkers; }
    public void setActiveWorkers(int value) { activeWorkers = value; }
    public int getMaxQueue() { return maxQueue; }
    public void setMaxQueue(int value) { maxQueue = value; }
    public Duration getCleanupInterval() { return cleanupInterval; }
    public void setCleanupInterval(Duration value) { cleanupInterval = value; }
    public String getFfmpegPath() { return ffmpegPath; }
    public void setFfmpegPath(String value) { ffmpegPath = value; }
    public Path getCacheDir() { return cacheDir; }
    public void setCacheDir(Path value) { cacheDir = value; }

    @PostConstruct
    void validate() {
        if (maxUrlLength <= 0 || maxGlobalStreams <= 0 || maxStreamsPerIp <= 0
                || maxResolutionsPerMinute <= 0 || maxHeight <= 0 || activeWorkers <= 0 || maxQueue < 0) {
            throw new IllegalArgumentException("Os limites numéricos do YouTube devem ser válidos");
        }
        if (resolutionTimeout == null || resolutionTimeout.isNegative() || resolutionTimeout.isZero()
                || upstreamConnectTimeout == null || upstreamConnectTimeout.isNegative() || upstreamConnectTimeout.isZero()
                || upstreamReadTimeout == null || upstreamReadTimeout.isNegative() || upstreamReadTimeout.isZero()
                || maxDuration == null || maxDuration.isNegative() || maxDuration.isZero()
                || preparationTimeout == null || preparationTimeout.isNegative() || preparationTimeout.isZero()
                || readyTtl == null || readyTtl.isNegative() || readyTtl.isZero()
                || failedTtl == null || failedTtl.isNegative() || failedTtl.isZero()
                || cleanupInterval == null || cleanupInterval.isNegative() || cleanupInterval.isZero()) {
            throw new IllegalArgumentException("As durações do YouTube devem ser positivas");
        }
        if (maxInputSize == null || maxInputSize.toBytes() <= 0
                || cacheSize == null || cacheSize.toBytes() <= 0
                || cacheDir == null || ffmpegPath == null || ffmpegPath.isBlank()) {
            throw new IllegalArgumentException("Os caminhos e tamanhos do YouTube devem ser válidos");
        }
    }
}
