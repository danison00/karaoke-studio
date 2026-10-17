package com.karaokestudio.youtube;

public record HttpRange(Long start, Long end) {

    public static HttpRange parse(String raw) {
        if (raw == null || raw.isBlank() || !raw.equals(raw.trim()) || !raw.startsWith("bytes=")) {
            throw new RangeNotSatisfiableException("O header Range deve usar bytes=start-end");
        }
        var value = raw.substring("bytes=".length());
        if (value.contains(",")) {
            throw new RangeNotSatisfiableException("Somente um intervalo Range é suportado");
        }
        var parts = value.split("-", -1);
        if (parts.length != 2 || (parts[0].isBlank() && parts[1].isBlank())) {
            throw new RangeNotSatisfiableException("O intervalo Range é inválido");
        }
        try {
            var start = parts[0].isBlank() ? null : Long.valueOf(parts[0]);
            var end = parts[1].isBlank() ? null : Long.valueOf(parts[1]);
            if (start != null && start < 0 || end != null && end < 0 || start != null && end != null && start > end) {
                throw new RangeNotSatisfiableException("O intervalo Range é inválido");
            }
            return new HttpRange(start, end);
        } catch (NumberFormatException exception) {
            throw new RangeNotSatisfiableException("O intervalo Range é inválido");
        }
    }

    public String headerValue() {
        return "bytes=" + (start == null ? "" : start) + "-" + (end == null ? "" : end);
    }
}
