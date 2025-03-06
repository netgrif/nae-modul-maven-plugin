package com.netgrif.maven.plugin.module;

public class HostApplicationNotFoundException extends RuntimeException {

    public HostApplicationNotFoundException() {
    }

    public HostApplicationNotFoundException(String message) {
        super(message);
    }

    public HostApplicationNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
