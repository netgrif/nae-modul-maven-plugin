package com.netgrif.maven.plugin.module.assembly;

import org.dom4j.Element;

public class FileSetsBuilder extends NestedBuilder<AssemblyDescriptorBuilder>{

    private final Element fileSets;

    public FileSetsBuilder(AssemblyDescriptorBuilder parentBuilder, Element fileSets) {
        super(parentBuilder);
        this.fileSets = fileSets;
    }

    public FileSetBuilder fileSet() {
        Element fileSet = fileSets.addElement("fileSet");
        return new FileSetBuilder(this, fileSet);
    }

}
