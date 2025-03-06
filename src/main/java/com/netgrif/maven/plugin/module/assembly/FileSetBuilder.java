package com.netgrif.maven.plugin.module.assembly;

import org.dom4j.Element;

public class FileSetBuilder extends NestedBuilder<FileSetsBuilder> {

    private final Element fileSet;
    private Element includes;
    private Element excludes;

    public FileSetBuilder(FileSetsBuilder parentBuilder, Element fileSet) {
        super(parentBuilder);
        this.fileSet = fileSet;
    }

    public FileSetBuilder useDefaultExcludes(Boolean value) {
        fileSet.addElement("useDefaultExcludes").setText(value.toString());
        return this;
    }

    public FileSetBuilder fileMode(String mode) {
        fileSet.addElement("fileMode").setText(mode);
        return this;
    }

    public FileSetBuilder directoryMode(String mode) {
        fileSet.addElement("directoryMode").setText(mode);
        return this;
    }

    public FileSetBuilder lineEnding(String ending) {
        fileSet.addElement("lineEnding").setText(ending);
        return this;
    }

    public FileSetBuilder filtered(Boolean value) {
        fileSet.addElement("filtered").setText(value.toString());
        return this;
    }

    public FileSetBuilder directory(String dir) {
        fileSet.addElement("directory").setText(dir);
        return this;
    }

    public FileSetBuilder outputDirectory(String dir) {
        fileSet.addElement("outputDirectory").setText(dir);
        return this;
    }

    public FileSetBuilder include(String file) {
        if (includes == null) {
            this.includes = fileSet.addElement("includes");
        }
        includes.addElement("include").setText(file);
        return this;
    }

    public FileSetBuilder exclude(String file) {
        if (excludes == null) {
            this.excludes = fileSet.addElement("excludes");
        }
        excludes.addElement("exclude").setText(file);
        return this;
    }

}
