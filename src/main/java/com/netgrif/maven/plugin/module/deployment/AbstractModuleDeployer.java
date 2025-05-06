package com.netgrif.maven.plugin.module.deployment;

import com.netgrif.maven.plugin.module.parameters.ModuleArtifact;

public abstract class AbstractModuleDeployer {

    public abstract boolean deploy(ModuleArtifact module);
}
