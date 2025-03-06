package com.netgrif.maven.plugin.module.assembly;

import org.dom4j.Element;

public class DependencySetsBuilder extends NestedBuilder<AssemblyDescriptorBuilder> {

    private final Element sets;

    public DependencySetsBuilder(AssemblyDescriptorBuilder parentBuilder, Element sets) {
        super(parentBuilder);
        this.sets = sets;
    }

    public DependencySetBuilder dependencySet() {
        Element set = sets.addElement("dependencySet");
        return new DependencySetBuilder(this, set);
    }

}
