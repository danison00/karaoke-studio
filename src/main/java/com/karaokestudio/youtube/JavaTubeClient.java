package com.karaokestudio.youtube;

public interface JavaTubeClient {

    String title() throws Exception;

    long durationSeconds() throws Exception;

    JavaTubeStreamQuery streams() throws Exception;
}
