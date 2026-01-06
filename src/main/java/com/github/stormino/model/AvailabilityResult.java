package com.github.stormino.model;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class AvailabilityResult {
    private boolean available;
    private Set<String> availableLanguages;
}
