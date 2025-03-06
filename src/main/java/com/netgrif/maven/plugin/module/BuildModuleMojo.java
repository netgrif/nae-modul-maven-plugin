package com.netgrif.maven.plugin.module;

import com.netgrif.maven.plugin.module.assembly.AssemblyDescriptorBuilder;
import com.netgrif.maven.plugin.module.assembly.DependencySetBuilder;
import com.netgrif.maven.plugin.module.parameters.SimpleArtifact;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyCollectorBuilder;
import org.apache.maven.shared.dependency.graph.DependencyNode;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

@Mojo(name = "build", defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class BuildModuleMojo extends AbstractMojo {

    private final Log log = getLog();

    @Component
    private MavenProject project;

    @Component
    private MavenSession session;

    @Component
    private BuildPluginManager pluginManager;

    @Component(hint = "default")
    private DependencyCollectorBuilder dependencyCollectorBuilder;

    @Parameter(property = "host-app")
    private SimpleArtifact hostApp;

    @Parameter(property = "excludes")
    private List<String> excludes = new ArrayList<>();

    @Parameter(property = "singleOutput")
    private boolean singleOutput = true;

    @Override
    public void execute() throws MojoExecutionException {
        ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        buildingRequest.setProject(project);
        try {
            // Collect dependencies
            DependencyNode rootNode = dependencyCollectorBuilder.collectDependencyGraph(buildingRequest, null);
            Set<DependencyNode> hostAppDependencies = new HashSet<>();
            DependencyNode hostDep = null;
            if (hostApp != null && hostApp.isValid()) {
                hostDep = findDependency(hostApp.getGroupId(), hostApp.getArtifactId(), hostApp.getVersion(), rootNode);
                if (hostDep == null || hostDep.getArtifact() == null)
                    throw new HostApplicationNotFoundException("Cannot find host app artifact as dependency: " + hostApp);
                if (log.isDebugEnabled())
                    log.debug("Host app dependency: " + hostDep.getArtifact());
                hostAppDependencies = flattenDependencies(hostDep);
                log.info("Dependencies aggregated " + hostAppDependencies.size() + " from host app");
                hostAppDependencies.forEach(d -> {
                    if (log.isDebugEnabled())
                        log.debug(d.getArtifact().toString());
                });
            }

            // Build assembly descriptor
            AssemblyDescriptorBuilder assembly = new AssemblyDescriptorBuilder();
            assembly.id(project.getArtifactId() + "-assembly-descriptor");
            if (singleOutput) {
                singleDescriptor(assembly, hostDep, hostAppDependencies);
            } else {
                separateDescriptor(assembly, hostDep, hostAppDependencies);
            }
            File targetDir = new File(project.getBuild().getDirectory() + File.separator + "assembly");
            targetDir.mkdirs();
            File descriptor = assembly.build(targetDir.getPath());
            if (log.isDebugEnabled())
                log.debug("maven-assembly-plugin descriptor saved on path: " + descriptor.getAbsolutePath());

            // Executes maven-assembly-plugin to build the resulting package
            executeMojo(
                    plugin(
                            groupId("org.apache.maven.plugins"),
                            artifactId("maven-assembly-plugin"),
                            version("3.6.0")
                    ),
                    goal("single"),
                    configuration(
                            element("appendAssemblyId", "false"),
                            element("descriptors", element("descriptor", descriptor.getPath()))
                    ),
                    executionEnvironment(project, session, pluginManager)
            );

        } catch (Exception e) {
            throw new MojoExecutionException(e);
        }
    }

    private DependencyNode findDependency(String groupId, String artifactId, String version, DependencyNode node) {
        Artifact depArtifact = node.getArtifact();
        if (depArtifact.getGroupId().equalsIgnoreCase(groupId) && depArtifact.getArtifactId().equalsIgnoreCase(artifactId) && depArtifact.getVersion().equalsIgnoreCase(version)) {
            return node;
        }
        if (!node.getChildren().isEmpty()) {
            for (DependencyNode child : node.getChildren()) {
                DependencyNode found = findDependency(groupId, artifactId, version, child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Set<DependencyNode> flattenDependencies(DependencyNode parent) {
        Set<DependencyNode> dependencies = new HashSet<>();
        if (!parent.getChildren().isEmpty()) {
            parent.getChildren().forEach(dep -> {
                dependencies.add(dep);
                dependencies.addAll(flattenDependencies(dep));
            });
        }
        return dependencies;
    }

    public void separateDescriptor(AssemblyDescriptorBuilder assembly, DependencyNode hostDep, Set<DependencyNode> hostAppDependencies) {
        assembly.format("zip");
        assembly.includeBaseDirectory(false);
        DependencySetBuilder depSet = assembly.dependencySets()
                .dependencySet()
                .outputDirectory("/")
                .useProjectArtifact(false)
                .useTransitiveFiltering(true)
                .unpack(false)
                .scope("runtime");
        if (hostDep != null)
            depSet.exclude(hostDep);
        hostAppDependencies.forEach(depSet::exclude);
        if (log.isDebugEnabled())
            log.debug("Excluding extra dependencies: " + excludes);
        excludes.forEach(depSet::exclude);
    }

    public void singleDescriptor(AssemblyDescriptorBuilder assembly, DependencyNode hostDep, Set<DependencyNode> hostAppDependencies) {
        assembly.format("zip");
        assembly.includeBaseDirectory(false);
        assembly.fileSets().fileSet()
                .directory(project.getBuild().getDirectory())
                .outputDirectory("/")
                .include(project.getBuild().getFinalName() + "." + project.getPackaging());
        DependencySetBuilder depSet = assembly.dependencySets()
                .dependencySet()
                .outputDirectory("/libs")
                .useProjectArtifact(false)
                .useTransitiveFiltering(true)
                .unpack(false)
                .scope("runtime");
        if (hostDep != null)
            depSet.exclude(hostDep);
        hostAppDependencies.forEach(depSet::exclude);
        if (log.isDebugEnabled())
            log.debug("Excluding extra dependencies: " + excludes);
        excludes.forEach(depSet::exclude);
    }

}
