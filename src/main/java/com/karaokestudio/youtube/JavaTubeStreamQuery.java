package com.karaokestudio.youtube;

import java.util.List;

public interface JavaTubeStreamQuery {

    YoutubeStream defaultResolution() throws Exception;

    YoutubeStream onlyAudio() throws Exception;

    List<YoutubeStream> all() throws Exception;
}
