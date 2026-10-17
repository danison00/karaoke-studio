package com.karaokestudio.youtube.preparation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public class HlsMediaCache {

    private static final Pattern RESOURCE = Pattern.compile("(?:index\\.m3u8|init\\.mp4|segment-\\d{6}\\.m4s)");

    private final Path root;

    public HlsMediaCache(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível criar o cache HLS", exception);
        }
    }

    public Path createGeneration(UUID sessionId, long generation) {
        if (sessionId == null || generation < 0) {
            throw new IllegalArgumentException("Sessão ou geração inválida");
        }
        var directory = root.resolve(sessionId.toString()).resolve("generation-" + generation).normalize();
        if (!directory.startsWith(root)) {
            throw new IllegalArgumentException("Diretório HLS fora do cache");
        }
        try {
            Files.createDirectories(directory);
            return directory;
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível criar a geração HLS", exception);
        }
    }

    public Optional<Path> resolveResource(UUID sessionId, long generation, String resourceName) {
        if (sessionId == null || generation < 0 || resourceName == null || !RESOURCE.matcher(resourceName).matches()) {
            return Optional.empty();
        }
        var generationDirectory = root.resolve(sessionId.toString()).resolve("generation-" + generation).normalize();
        var candidate = generationDirectory.resolve(resourceName).normalize();
        if (!generationDirectory.startsWith(root) || !candidate.startsWith(generationDirectory)
                || !Files.isRegularFile(candidate)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    public void publish(Path temporary, Path published) {
        if (temporary == null || published == null) {
            throw new IllegalArgumentException("Arquivos HLS obrigatórios");
        }
        var source = temporary.toAbsolutePath().normalize();
        var target = published.toAbsolutePath().normalize();
        if (!source.startsWith(root) || !target.startsWith(root)) {
            throw new IllegalArgumentException("Publicação HLS fora do cache");
        }
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AccessDeniedException exception) {
                // Some Windows filesystem providers reject rename of a freshly-created
                // temporary file. Copying and deleting keeps the public name invisible
                // until the complete file is present and is the safe fallback there.
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                Files.delete(source);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível publicar recurso HLS", exception);
        }
    }

    public void discardSession(UUID sessionId) {
        if (sessionId == null) return;
        var directory = root.resolve(sessionId.toString()).normalize();
        if (!directory.startsWith(root) || !Files.isDirectory(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
