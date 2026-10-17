package com.karaokestudio.youtube.preparation;

import java.util.Objects;

public record HlsRange(long generation, long fromMs, long toMs, String playlistUrl) {

    public HlsRange {
        if (generation < 0 || fromMs < 0 || toMs < fromMs) {
            throw new IllegalArgumentException("Intervalo HLS inválido");
        }
        playlistUrl = Objects.requireNonNull(playlistUrl, "playlistUrl");
        if (playlistUrl.isBlank() || !playlistUrl.startsWith("/")) {
            throw new IllegalArgumentException("A playlist HLS deve ser same-origin");
        }
    }
}
