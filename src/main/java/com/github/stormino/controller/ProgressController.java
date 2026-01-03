package com.github.stormino.controller;

import com.github.stormino.service.ProgressBroadcastService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/progress")
@RequiredArgsConstructor
public class ProgressController {
    
    private final ProgressBroadcastService progressBroadcastService;
    
    /**
     * SSE endpoint for real-time progress updates
     */
    @GetMapping("/stream")
    public SseEmitter streamProgress() {
        log.info("New SSE connection established");
        return progressBroadcastService.createEmitter();
    }
}
