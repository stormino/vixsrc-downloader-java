package com.github.stormino.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PlaylistInfo {
    
    private String url;
    private String language;
    private boolean verified;
    
    public static PlaylistInfo of(String url, String language) {
        return PlaylistInfo.builder()
                .url(url)
                .language(language)
                .verified(false)
                .build();
    }
}
