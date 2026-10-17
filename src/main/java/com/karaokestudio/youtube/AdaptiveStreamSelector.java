package com.karaokestudio.youtube;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AdaptiveStreamSelector {

    private static final Pattern HEIGHT_PATTERN = Pattern.compile("(\\d{3,5})p", Pattern.CASE_INSENSITIVE);

    public Optional<AdaptiveStreamPair> select(List<YoutubeStream> streams, int maxHeight) {
        if (streams == null || streams.isEmpty() || maxHeight <= 0) {
            return Optional.empty();
        }

        var direct = streams.stream()
                .filter(this::isDirectUsable)
                .toList();
        var videos = direct.stream()
                .filter(this::isVideoOnly)
                .filter(stream -> height(stream) <= 0 || height(stream) <= maxHeight)
                .toList();
        var audios = direct.stream()
                .filter(this::isAudioOnly)
                .toList();

        if (videos.isEmpty() || audios.isEmpty()) {
            return Optional.empty();
        }

        var video = videos.stream().max(videoComparator()).orElse(null);
        var audio = audios.stream().max(audioComparator()).orElse(null);
        return video == null || audio == null ? Optional.empty() : Optional.of(new AdaptiveStreamPair(video, audio));
    }

    private boolean isDirectUsable(YoutubeStream stream) {
        return stream != null
                && stream.upstreamUri() != null
                && !stream.sabr()
                && !stream.otf();
    }

    private boolean isVideoOnly(YoutubeStream stream) {
        return stream.includesVideo()
                && !stream.includesAudio()
                && mediaType(stream).startsWith("video/");
    }

    private boolean isAudioOnly(YoutubeStream stream) {
        return stream.includesAudio()
                && !stream.includesVideo()
                && mediaType(stream).startsWith("audio/");
    }

    private Comparator<YoutubeStream> videoComparator() {
        return Comparator
                .comparingInt((YoutubeStream stream) -> compatibleVideo(stream) ? 1 : 0)
                .thenComparingInt(this::height)
                .thenComparingInt(YoutubeStream::fps)
                .thenComparingLong(YoutubeStream::bitrate);
    }

    private Comparator<YoutubeStream> audioComparator() {
        return Comparator
                .comparingInt((YoutubeStream stream) -> stream.defaultAudioTrack() ? 1 : 0)
                .thenComparingInt((YoutubeStream stream) -> compatibleAudio(stream) ? 1 : 0)
                .thenComparingLong(YoutubeStream::bitrate);
    }

    private boolean compatibleVideo(YoutubeStream stream) {
        var codec = lower(stream.videoCodec());
        return mediaType(stream).equals("video/mp4") && (codec.isBlank() || codec.contains("avc1") || codec.contains("avc3"));
    }

    private boolean compatibleAudio(YoutubeStream stream) {
        var codec = lower(stream.audioCodec());
        return mediaType(stream).equals("audio/mp4") && (codec.isBlank() || codec.contains("mp4a") || codec.contains("aac"));
    }

    private int height(YoutubeStream stream) {
        var resolution = stream.resolution();
        if (resolution == null) {
            return 0;
        }
        Matcher matcher = HEIGHT_PATTERN.matcher(resolution);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private String mediaType(YoutubeStream stream) {
        var contentType = stream.contentType();
        if (contentType == null) {
            return "";
        }
        var separator = contentType.indexOf(';');
        return lower(separator < 0 ? contentType : contentType.substring(0, separator)).trim();
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
