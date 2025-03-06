package com.netgrif.maven.plugin.module.assembly;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.dom4j.Element;

public class DependencySetBuilder extends NestedBuilder<DependencySetsBuilder> {

    private final Element set;
    private Element excludes;
    private Element includes;

    public DependencySetBuilder(DependencySetsBuilder parentBuilder, Element set) {
        super(parentBuilder);
        this.set = set;
    }

    public DependencySetBuilder exclude(String dep) {
        if (excludes == null) {
            this.excludes = set.addElement("excludes");
        }
        excludes.addElement("exclude").setText(dep);
        return this;
    }

    public DependencySetBuilder exclude(DependencyNode dependency) {
        Artifact artifact = dependency.getArtifact();
        String dep = artifact.getGroupId() + ":"
                + artifact.getArtifactId() + ":"
                + "*:"
                + "*:"
                + artifact.getVersion();
        return exclude(dep);
    }

    public DependencySetBuilder include(String dep) {
        if (includes == null) {
            this.includes = set.addElement("includes");
        }
        includes.addElement("include").setText(dep);
        return this;
    }

    public DependencySetBuilder outputDirectory(String dir) {
        set.addElement("outputDirectory").setText(dir);
        return this;
    }

    public DependencySetBuilder useProjectArtifact(Boolean dir) {
        set.addElement("useProjectArtifact").setText(dir.toString());
        return this;
    }

    public DependencySetBuilder useTransitiveFiltering(Boolean dir) {
        set.addElement("useTransitiveFiltering").setText(dir.toString());
        return this;
    }

    public DependencySetBuilder unpack(Boolean dir) {
        set.addElement("unpack").setText(dir.toString());
        return this;
    }

    public DependencySetBuilder scope(String dir) {
        set.addElement("scope").setText(dir);
        return this;
    }
}
