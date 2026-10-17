package com.karaokestudio.youtube.preparation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import com.karaokestudio.youtube.UpstreamStreamClient;
import com.karaokestudio.youtube.YoutubeStream;

public class SecureStreamDownloader {

    private final UpstreamStreamClient upstreamClient;

    public SecureStreamDownloader(UpstreamStreamClient upstreamClient) {
        this.upstreamClient = Objects.requireNonNull(upstreamClient);
    }

    public long download(YoutubeStream stream, Path destination, long maxBytes) {
        Objects.requireNonNull(stream);
        Objects.requireNonNull(destination);
        if (stream.upstreamUri() == null) {
            throw new IllegalArgumentException("O stream não possui URL direta");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("O limite de bytes deve ser positivo");
        }

        try {
            Files.createDirectories(destination.toAbsolutePath().normalize().getParent());
            try (var response = upstreamClient.open(stream.upstreamUri(), null)) {
                if (response.status() >= 400) {
                    throw new IOException("O upstream recusou o download com HTTP " + response.status());
                }
                if (response.contentLength() > maxBytes) {
                    throw new MediaTooLargeException("A mídia excede o limite configurado");
                }
                try (var input = response.body();
                     var output = Files.newOutputStream(destination, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    return copyBounded(input, output, maxBytes);
                }
            }
        } catch (RuntimeException | IOException exception) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException ignored) {
                // The original failure is more useful to the caller.
            }
            if (exception instanceof MediaTooLargeException mediaTooLarge) {
                throw mediaTooLarge;
            }
            throw new RuntimeException("Não foi possível baixar o stream upstream", exception);
        }
    }

    private long copyBounded(InputStream input, java.io.OutputStream output, long maxBytes) throws IOException {
        var buffer = new byte[64 * 1024];
        long copied = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            copied += read;
            if (copied > maxBytes) {
                throw new MediaTooLargeException("A mídia excede o limite configurado");
            }
            output.write(buffer, 0, read);
        }
        output.flush();
        return copied;
    }
}
