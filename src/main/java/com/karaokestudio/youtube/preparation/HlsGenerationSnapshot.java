package com.karaokestudio.youtube.preparation;

public record HlsGenerationSnapshot(
        long generation,
        long fromMs,
        long toMs,
        String playlistUrl,
        boolean playable,
        boolean ended) {

    public HlsGenerationSnapshot {
        if (generation < 0 || fromMs < 0 || toMs < fromMs) {
            throw new IllegalArgumentException("Snapshot HLS inválido");
        }
        if (playlistUrl == null || playlistUrl.isBlank() || !playlistUrl.startsWith("/")) {
            throw new IllegalArgumentException("A playlist HLS deve ser same-origin");
        }
    }

    public HlsRange range() {
        return new HlsRange(generation, fromMs, toMs, playlistUrl);
    }
}
