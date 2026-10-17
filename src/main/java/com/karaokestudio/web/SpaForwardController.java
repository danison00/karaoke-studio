package com.karaokestudio.web;

import java.io.IOException;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardController {

    private final Resource index = new ClassPathResource("static/index.html");

    @GetMapping({"/", "/index.html"})
    public ResponseEntity<Resource> forwardToSpa() throws IOException {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .contentType(MediaType.TEXT_HTML)
                .body(index);
    }
}
