package com.netgrif.maven.plugin.module.parameters;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.maven.artifact.Artifact;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SimpleArtifact {

    public static final String SEPARATOR = ":";

    private String groupId;
    private String artifactId;
    private String version;

    public SimpleArtifact(String artifact) {
        String[] split = artifact.split(SEPARATOR);
        this.groupId = split[0];
        if (split.length > 1) {
            this.artifactId = split[1];
            if (split.length > 2) {
                this.version = split[2];
            }
        }
    }

    public boolean equalsToArtifact(Artifact artifact) {
        return this.groupId.equals(artifact.getGroupId()) && this.artifactId.equals(artifact.getArtifactId()) && this.version.equals(artifact.getVersion());
    }

    public boolean isValid() {
        return !groupId.isBlank() && !artifactId.isBlank() && !version.isBlank();
    }

    @Override
    public String toString() {
        return groupId + SEPARATOR + artifactId + SEPARATOR + version;
    }
}
