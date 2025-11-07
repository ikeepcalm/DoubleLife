package dev.ua.ikeepcalm.doublelife.domain.model.source;

import lombok.Getter;

@Getter
public enum DoubleLifeMode {

    DEFAULT("Default Mode", "Commands only accessible in DoubleLife mode"),
    TURBO("Turbo Mode", "Full admin permissions with command monitoring");

    private final String displayName;
    private final String description;

    DoubleLifeMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

}