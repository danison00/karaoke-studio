package com.karaokestudio.youtube.ffmpeg;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

public interface FfmpegRunner {

    void mux(Path video, Path audio, Path outputPart, MuxProfile profile, Duration timeout);

    default FfmpegProcess startHls(
            URI videoRelay,
            URI audioRelay,
            Path generationDirectory,
            FfmpegHlsProfile profile,
            long positionMs,
            Duration timeout) {
        throw new UnsupportedOperationException("HLS execution is not supported by this runner");
    }
}
