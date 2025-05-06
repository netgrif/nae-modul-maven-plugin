package com.netgrif.maven.plugin.module.deployment;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModuleRepository {

    private String serverId;
    private String url;
    private ModuleRepositoryType type;

    public static enum ModuleRepositoryType {
        nexus,
        ftp,
        http;
    }

}
