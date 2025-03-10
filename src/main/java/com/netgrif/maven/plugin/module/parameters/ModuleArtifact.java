package com.netgrif.maven.plugin.module.parameters;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModuleArtifact {

    public static final String SEPARATOR = "-";

    private String name;
    private String version;

    public ModuleArtifact(String modul) {
        String[] split = modul.split(SEPARATOR);
        this.name = split[0];
        this.version = split[1];
    }

    @Override
    public String toString() {
        return name + SEPARATOR + version;
    }

}
