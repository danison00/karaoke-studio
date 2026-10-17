package com.karaokestudio.youtube.ffmpeg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProcessCommandExecutor implements CommandExecutor {

    private static final int MAX_CAPTURED_OUTPUT = 64 * 1024;
    private static final Logger log = LoggerFactory.getLogger(ProcessCommandExecutor.class);

    @Override
    public CommandResult execute(List<String> command, Duration timeout) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(timeout);
        if (command.isEmpty() || command.stream().anyMatch(Objects::isNull)) {
            throw new FfmpegExecutionException("Comando externo vazio ou inválido");
        }

        final Process process;
        try {
            log.debug("youtube.ffmpeg.process.start executable={} argumentCount={} timeoutMs={}",
                    command.getFirst(), command.size(), timeout.toMillis());
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            log.error("youtube.ffmpeg.process.start-failed executable={} message={}",
                    command.getFirst(), exception.getMessage(), exception);
            throw new FfmpegExecutionException("Não foi possível iniciar o processo externo", exception);
        }

        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> readBounded(process.getInputStream()), readers);
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readBounded(process.getErrorStream()), readers);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroy();
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
                throw new FfmpegTimeoutException("Processo externo excedeu o tempo limite");
            }
            var result = new CommandResult(process.exitValue(), await(stdout), await(stderr));
            log.debug("youtube.ffmpeg.process.finished executable={} exitCode={} stdoutBytes={} stderrBytes={}",
                    command.getFirst(), result.exitCode(), result.stdout().length(), result.stderr().length());
            return result;
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new FfmpegExecutionException("Execução externa interrompida", exception);
        } catch (ExecutionException exception) {
            throw new FfmpegExecutionException("Não foi possível ler a saída do processo externo", exception.getCause());
        }
    }

    @Override
    public FfmpegProcess start(List<String> command, Duration timeout) {
        return start(command, timeout, null);
    }

    @Override
    public FfmpegProcess start(List<String> command, Duration timeout, Path workingDirectory) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(timeout);
        if (command.isEmpty() || command.stream().anyMatch(Objects::isNull)) {
            throw new FfmpegExecutionException("Comando externo vazio ou inválido");
        }

        final Process process;
        try {
            log.debug("youtube.ffmpeg.process.start-async executable={} argumentCount={} timeoutMs={}",
                    command.getFirst(), command.size(), timeout.toMillis());
            var processBuilder = new ProcessBuilder(command);
            if (workingDirectory != null) {
                processBuilder.directory(workingDirectory.toFile());
            }
            process = processBuilder.start();
        } catch (IOException exception) {
            log.error("youtube.ffmpeg.process.start-async-failed executable={} message={}",
                    command.getFirst(), exception.getMessage(), exception);
            throw new FfmpegExecutionException("Não foi possível iniciar o processo externo", exception);
        }

        var readers = Executors.newVirtualThreadPerTaskExecutor();
        var stdout = CompletableFuture.supplyAsync(() -> readBounded(process.getInputStream()), readers);
        var stderr = CompletableFuture.supplyAsync(() -> readBounded(process.getErrorStream()), readers);
        var completion = CompletableFuture.supplyAsync(() -> {
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroy();
                    if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                    }
                    throw new FfmpegTimeoutException("Processo externo excedeu o tempo limite");
                }
                var result = new CommandResult(process.exitValue(), stdout.get(), stderr.get());
                log.debug("youtube.ffmpeg.process.finished-async executable={} exitCode={} stdoutBytes={} stderrBytes={}",
                        command.getFirst(), result.exitCode(), result.stdout().length(), result.stderr().length());
                return result;
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new FfmpegExecutionException("Execução externa interrompida", exception);
            } catch (ExecutionException exception) {
                throw new FfmpegExecutionException("Não foi possível ler a saída do processo externo", exception.getCause());
            } finally {
                readers.close();
            }
        });
        return new ManagedFfmpegProcess(process, completion);
    }

    private String await(CompletableFuture<String> output) throws InterruptedException, ExecutionException {
        return output.get();
    }

    private String readBounded(InputStream input) {
        try (input) {
            var output = new ByteArrayOutputStream();
            var buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() < MAX_CAPTURED_OUTPUT) {
                    output.write(buffer, 0, Math.min(read, MAX_CAPTURED_OUTPUT - output.size()));
                }
            }
            return output.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new FfmpegExecutionException("Não foi possível ler a saída do processo externo", exception);
        }
    }

    private static final class ManagedFfmpegProcess implements FfmpegProcess {
        private final Process process;
        private final CompletableFuture<CommandResult> completion;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private ManagedFfmpegProcess(Process process, CompletableFuture<CommandResult> completion) {
            this.process = process;
            this.completion = completion;
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            process.destroy();
            Thread.startVirtualThread(() -> {
                try {
                    if (process.waitFor(2, TimeUnit.SECONDS) == false) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException exception) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            });
        }

        @Override
        public java.util.concurrent.CompletionStage<CommandResult> completion() {
            return completion;
        }
    }
}
