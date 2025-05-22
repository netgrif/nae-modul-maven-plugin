package com.netgrif.maven.plugin.module.parameters;

import lombok.Data;
import org.apache.maven.artifact.Artifact;

@Data
public class SimpleArtifact {

    public static final String SEPARATOR = ":";

    private String groupId;
    private String artifactId;
    private String version;

    public SimpleArtifact(String artifact) {
        if (artifact == null || artifact.isBlank()) return;
        String[] split = artifact.split(SEPARATOR);
        this.groupId = split[0];
        if (split.length > 1) {
            this.artifactId = split[1];
            if (split.length > 2) {
                this.version = split[2];
            }
        }
    }

    public SimpleArtifact() {
    }

    public SimpleArtifact(String groupId, String artifactId, String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    public boolean equalsToArtifact(Artifact artifact) {
        if (artifact == null) return false;
        return this.groupId.equals(artifact.getGroupId()) && this.artifactId.equals(artifact.getArtifactId()) && this.version.equals(artifact.getVersion());
    }

    public boolean isValid() {
        return groupId != null && !groupId.isBlank() &&
                artifactId != null && !artifactId.isBlank() &&
                version != null && !version.isBlank();
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return groupId + SEPARATOR + artifactId + SEPARATOR + version;
    }
}
