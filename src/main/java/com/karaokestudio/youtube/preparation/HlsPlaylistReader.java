package com.karaokestudio.youtube.preparation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public class HlsPlaylistReader {

    private static final Pattern RESOURCE = Pattern.compile("(?:init\\.mp4|segment-\\d{6}\\.m4s)");

    public Optional<HlsGenerationSnapshot> inspect(
            Path generationDirectory,
            long generation,
            long logicalOffsetMs,
            String playlistUrl) {
        if (generationDirectory == null || generation < 0 || logicalOffsetMs < 0) {
            return Optional.empty();
        }
        var playlist = generationDirectory.resolve("index.m3u8").normalize();
        if (!playlist.startsWith(generationDirectory.normalize()) || !Files.isRegularFile(playlist)) {
            return Optional.empty();
        }
        try {
            var lines = Files.readAllLines(playlist);
            var segmentDurations = new ArrayList<Double>();
            var init = false;
            var ended = false;
            for (int i = 0; i < lines.size(); i++) {
                var line = lines.get(i).trim();
                if (line.startsWith("#EXT-X-MAP:")) {
                    var uri = attribute(line, "URI");
                    init = uri != null && RESOURCE.matcher(uri).matches() && regular(generationDirectory, uri);
                } else if (line.startsWith("#EXTINF:")) {
                    var duration = Double.parseDouble(line.substring("#EXTINF:".length()).split(",", 2)[0]);
                    if (i + 1 >= lines.size() || !RESOURCE.matcher(lines.get(i + 1).trim()).matches()
                            || !regular(generationDirectory, lines.get(++i).trim())) {
                        return Optional.empty();
                    }
                    segmentDurations.add(duration);
                } else if (line.equals("#EXT-X-ENDLIST")) {
                    ended = true;
                }
            }
            if (!init || segmentDurations.isEmpty()) {
                return Optional.empty();
            }
            var totalMs = Math.round(segmentDurations.stream().mapToDouble(Double::doubleValue).sum() * 1_000);
            return Optional.of(new HlsGenerationSnapshot(
                    generation, logicalOffsetMs, logicalOffsetMs + totalMs, playlistUrl, true, ended));
        } catch (IOException | NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private boolean regular(Path directory, String filename) {
        var candidate = directory.resolve(filename).normalize();
        return RESOURCE.matcher(filename).matches() && candidate.startsWith(directory.normalize())
                && Files.isRegularFile(candidate);
    }

    private String attribute(String line, String name) {
        var prefix = name + "=\"";
        var start = line.indexOf(prefix);
        if (start < 0) return null;
        var valueStart = start + prefix.length();
        var end = line.indexOf('"', valueStart);
        return end < 0 ? null : line.substring(valueStart, end);
    }
}
