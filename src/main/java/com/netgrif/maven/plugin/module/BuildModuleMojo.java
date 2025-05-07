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
import org.eclipse.sisu.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

/**
 * Execution to build module zip package with module jar and its unique dependencies.
 * It's assumed that a host application will be com.netgrif:application-engine, but it can be configured for other application.
 * The host application is used for the module dependency packaging as dependencies that also the host application has
 * are ignored and not packaged with the module.
 */
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

    @Parameter(property = "NAEVersion")
    private String naeVersion;

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
            SimpleArtifact hostAppArtifact = getHostAppArtifact();

            if (hostAppArtifact == null) {
                log.warn("Packaging module without host application");
            } else {
                hostDep = findDependency(hostAppArtifact.getGroupId(), hostAppArtifact.getArtifactId(), hostAppArtifact.getVersion(), rootNode);
                if (hostDep == null || hostDep.getArtifact() == null)
                    throw new HostApplicationNotFoundException("Cannot find host app artifact as dependency: " + hostAppArtifact);
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
            try {
                Files.createDirectories(targetDir.toPath());
                log.info("Successfully ensured target directory exists: " + targetDir.getAbsolutePath());
            } catch (IOException e) {
                throw new RuntimeException("Could not create target directory for package assembly: " + targetDir, e);
            }
            if (!targetDir.getParentFile().canWrite()) {
                throw new RuntimeException("Cannot write to parent directory: " + targetDir.getParent());
            }
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

    private SimpleArtifact getHostAppArtifact() {
        SimpleArtifact hostAppArtifact = null;
        if (hostApp == null && (naeVersion == null || naeVersion.isEmpty())) {
            log.warn("Host application was not found. Packaging module without host application");
            return null;
        }
        if (naeVersion != null && !naeVersion.isEmpty()) {
            hostAppArtifact = new SimpleArtifact(Constants.NETGRIF_GROUP_ID, Constants.NAE_ARTIFACT_ID, naeVersion);
        } else if (hostApp.isValid()) {
            hostAppArtifact = hostApp;
        }
        return hostAppArtifact;
    }

    private static DependencyNode findDependency(String groupId, String artifactId, String version, DependencyNode node) {
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

    private static Set<DependencyNode> flattenDependencies(DependencyNode parent) {
        Set<DependencyNode> dependencies = new HashSet<>();
        if (parent != null && !parent.getChildren().isEmpty()) {
            parent.getChildren().forEach(dep -> {
                dependencies.add(dep);
                dependencies.addAll(flattenDependencies(dep));
            });
        }
        return dependencies;
    }

    public void separateDescriptor(AssemblyDescriptorBuilder assembly, @Nullable DependencyNode hostDep, @Nullable Set<DependencyNode> hostAppDependencies) {
        assembly.format("zip");
        assembly.includeBaseDirectory(false);
        DependencySetBuilder depSet = assembly.dependencySets()
                .dependencySet()
                .outputDirectory("/")
                .useProjectArtifact(false)
                .useTransitiveFiltering(true)
                .unpack(false)
                .scope("runtime");
        if (hostDep != null) {
            depSet.exclude(hostDep);
        }
        if (hostAppDependencies != null) {
            hostAppDependencies.forEach(depSet::exclude);
        }
        if (log.isDebugEnabled())
            log.debug("Excluding extra dependencies: " + excludes);
        excludes.forEach(depSet::exclude);
    }

    /**
     * Configures an assembly descriptor to generate a single descriptor containing specific files
     * and dependencies, while excluding certain unnecessary ones. This method customizes the assembly
     * builder by defining file sets and dependency sets, including exclusions for dependencies specified.
     *
     * @param assembly            the {@link AssemblyDescriptorBuilder} used to configure the assembly descriptor
     * @param hostDep             the {@link DependencyNode} representing the main host dependency to exclude, can be null
     * @param hostAppDependencies the {@link Set} of {@link DependencyNode} representing the additional host application dependencies to exclude, can be null
     */
    public void singleDescriptor(AssemblyDescriptorBuilder assembly, @Nullable DependencyNode hostDep, @Nullable Set<DependencyNode> hostAppDependencies) {
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
        if (hostDep != null) {
            depSet.exclude(hostDep);
        }
        if (hostAppDependencies != null) {
            hostAppDependencies.forEach(depSet::exclude);
        }
        if (log.isDebugEnabled())
            log.debug("Excluding extra dependencies: " + excludes);
        excludes.forEach(depSet::exclude);
    }

}
