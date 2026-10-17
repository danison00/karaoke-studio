package com.karaokestudio.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import com.karaokestudio.youtube.HttpRange;
import com.karaokestudio.youtube.RangeNotSatisfiableException;
import com.karaokestudio.youtube.YoutubeMediaService;
import com.karaokestudio.youtube.preparation.PreparedMedia;
import com.karaokestudio.youtube.preparation.PreparationMode;

@Component
public class CachedMediaStreamer {

    public YoutubeMediaService.MediaStream open(PreparedMedia prepared, String rangeHeader) {
        if (prepared.mode() != PreparationMode.MUXED || prepared.file() == null) {
            throw new IllegalArgumentException("A mídia não é um arquivo muxado");
        }
        var file = prepared.file();
        try {
            var total = Files.size(file);
            if (total <= 0) {
                prepared.close();
                throw new IllegalStateException("O arquivo muxado está vazio");
            }

            long start = 0;
            long end = total - 1;
            var status = 200;
            String contentRange = null;
            if (rangeHeader != null && !rangeHeader.isBlank()) {
                var range = HttpRange.parse(rangeHeader);
                if (range.start() == null) {
                    if (range.end() == null || range.end() == 0) {
                        throw invalidRange(total);
                    }
                    start = Math.max(0, total - range.end());
                    end = total - 1;
                } else {
                    start = range.start();
                    end = range.end() == null ? total - 1 : Math.min(range.end(), total - 1);
                }
                if (start >= total || start > end) {
                    throw invalidRange(total);
                }
                status = 206;
                contentRange = "bytes " + start + "-" + end + "/" + total;
            }

            var input = Files.newInputStream(file);
            skipFully(input, start);
            var length = end - start + 1;
            var etag = "\"" + total + "-" + Files.getLastModifiedTime(file).toMillis() + "\"";
            return new YoutubeMediaService.MediaStream(
                    status,
                    prepared.contentType(),
                    length,
                    contentRange,
                    true,
                    PreparationMode.MUXED,
                    new LimitedInputStream(input, length),
                    prepared::close,
                    etag);
        } catch (IOException exception) {
            prepared.close();
            throw new IllegalStateException("Não foi possível abrir a mídia preparada", exception);
        } catch (RuntimeException exception) {
            prepared.close();
            throw exception;
        }
    }

    private static void skipFully(java.io.InputStream input, long bytes) throws IOException {
        var remaining = bytes;
        while (remaining > 0) {
            var skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            if (input.read() < 0) {
                throw new IOException("Não foi possível posicionar o stream no intervalo solicitado");
            }
            remaining--;
        }
    }

    private static RangeNotSatisfiableException invalidRange(long total) {
        return new RangeNotSatisfiableException("O intervalo solicitado não existe; tamanho total: " + total, total);
    }

    private static final class LimitedInputStream extends java.io.FilterInputStream {
        private long remaining;

        private LimitedInputStream(java.io.InputStream input, long remaining) {
            super(input);
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            var value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining == 0) {
                return -1;
            }
            var read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
