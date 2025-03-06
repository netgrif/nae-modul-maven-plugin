package com.netgrif.maven.plugin.module.assembly;

public abstract class NestedBuilder<T> {

    protected final T parentBuilder;

    protected NestedBuilder(T parentBuilder) {
        this.parentBuilder = parentBuilder;
    }

    public T up() {
        return parentBuilder;
    }
}
