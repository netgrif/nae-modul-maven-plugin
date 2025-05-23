package com.netgrif.maven.plugin.module;

import com.netgrif.maven.plugin.module.assembly.AssemblyDescriptorBuilder;
import com.netgrif.maven.plugin.module.assembly.DependencySetBuilder;
import com.netgrif.maven.plugin.module.parameters.SimpleArtifact;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Developer;
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

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

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

    /**
     * Returns the logger for this Mojo instance.
     *
     * @return the logger associated with this Mojo
     */
    private Log log() {
        return getLog();
    }

    /**
     * Represents the Maven project associated with the current build process.
     * This variable is a Spring component, allowing it to be injected and
     * managed by the Spring context. It provides metadata and configuration
     * details of the Maven project, which can include project version,
     * dependencies, plugins, and other build-related information.
     */
    @Component
    private MavenProject project;

    /**
     * Represents the Maven session associated with the current Maven build.
     * This object provides access to the runtime information of the Maven build
     * such as the current project, execution goals, and build lifecycle data.
     * It is injected as a dependency by the Spring framework.
     */
    @Component
    private MavenSession session;

    /**
     * Represents the Build Plugin Manager used to manage and control plugins
     * within the build system. This component is automatically injected and
     * facilitates interaction with various plugins, ensuring proper initialization
     * and execution during the build process.
     */
    @Component
    private BuildPluginManager pluginManager;

    /**
     * A builder component used for constructing DependencyCollector instances.
     * It is annotated as a component with the default hint, enabling it to
     * be injected and used in dependency injection environments.
     */
    @Component(hint = "default")
    private DependencyCollectorBuilder dependencyCollectorBuilder;

    /**
     * Represents the host application artifact to be used.
     * This is managed as a Maven parameter and can be set using the "host-app" property.
     * The variable holds an instance of {@link SimpleArtifact}, encapsulating the
     * details of the host application's artifact, such as its coordinates and version.
     */
    @Parameter(property = "host-app")
    private SimpleArtifact hostApp;

    /**
     * Represents the version information for the NAE (Network Attached Endpoint).
     * This property corresponds to the Maven parameter "NAEVersion".
     */
    @Parameter(property = "NAEVersion")
    private String naeVersion;

    /**
     * A list of patterns used to specify files or directories to be excluded from processing.
     * The values in this list are typically defined as strings representing exclusion patterns.
     * These patterns might follow a specific syntax suitable for filtering files or directories,
     * ensuring they are ignored during the operation or processing.
     */
    @Parameter(property = "excludes")
    private List<String> excludes = new ArrayList<>();

    /**
     * A boolean flag that determines if the operation should produce a single output.
     * If set to true, the operation will generate only one output.
     * If set to false, multiple outputs may be generated depending on the specific logic.
     */
    @Parameter(property = "singleOutput")
    private boolean singleOutput = true;

    /**
     * A flag indicating whether to forcefully enable the worker profile.
     * This parameter can be configured via a system property or build configuration.
     * If set to {@code true}, the worker profile will be enabled regardless of
     * other conditions. By default, this flag is set to {@code false}.
     */
    @Parameter(property = "forceEnableWorkerProfile", defaultValue = "false")
    private boolean forceEnableWorkerProfile = false;

    /**
     * Indicates whether a custom JAR file should be generated for the manifest output.
     * If set to {@code true}, the build process will create a custom manifest output JAR.
     * The default value is {@code false}.
     * <p>
     * This parameter can be configured using the property {@code customManifestOutputJar}.
     */
    @Parameter(property = "customManifestOutputJar", defaultValue = "false")
    private boolean customManifestOutputJar = false;

    /**
     * Executes the Maven Mojo to build a module ZIP package containing the module JAR and its unique dependencies, excluding those shared with a specified host application.
     *
     * <p>
     * This method orchestrates the following steps:
     * <ul>
     *   <li>Collects the project's dependency graph and identifies dependencies provided by the host application.</li>
     *   <li>Creates or modifies the module JAR with a custom manifest containing enriched project metadata.</li>
     *   <li>Generates Spring auto-configuration imports for classes annotated with {@code @Configuration} or {@code @AutoConfiguration}.</li>
     *   <li>Removes specific application and worker property files from the JAR as needed.</li>
     *   <li>Builds an assembly descriptor to package the module and its dependencies, excluding those present in the host application and any additional specified exclusions.</li>
     *   <li>Invokes the Maven Assembly Plugin to produce the final distributable ZIP package.</li>
     * </ul>
     * If the host application artifact cannot be found among the dependencies, a {@link HostApplicationNotFoundException} is thrown.
     *
     * @throws MojoExecutionException if any step in the packaging process fails
     */
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
                log().warn("Packaging module without host application");
            } else {
                hostDep = findDependency(hostAppArtifact.getGroupId(), hostAppArtifact.getArtifactId(), hostAppArtifact.getVersion(), rootNode);
                if (hostDep == null || hostDep.getArtifact() == null)
                    throw new HostApplicationNotFoundException("Cannot find host app artifact as dependency: " + hostAppArtifact);
                if (log().isDebugEnabled())
                    log().debug("Host app dependency: " + hostDep.getArtifact());
                hostAppDependencies = flattenDependencies(hostDep);
                log().info("Dependencies aggregated " + hostAppDependencies.size() + " from host app");
                hostAppDependencies.forEach(d -> {
                    if (log().isDebugEnabled())
                        log().debug(d.getArtifact().toString());
                });
            }

            try {
                createJarWithCustomManifest();
                generateSpringFactoriesToOutputDir();
                removeProperties();
            } catch (IOException ioEx) {
                log().error("Failed to create JAR with custom manifest", ioEx);
                throw new MojoExecutionException("Failed to create JAR with custom manifest", ioEx);
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
                log().info("Successfully ensured target directory exists: " + targetDir.getAbsolutePath());
            } catch (IOException e) {
                throw new RuntimeException("Could not create target directory for package assembly: " + targetDir, e);
            }
            if (!targetDir.getParentFile().canWrite()) {
                throw new RuntimeException("Cannot write to parent directory: " + targetDir.getParent());
            }
            File descriptor = assembly.build(targetDir.getPath());
            if (log().isDebugEnabled())
                log().debug("maven-assembly-plugin descriptor saved on path: " + descriptor.getAbsolutePath());

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

    /**
     * Updates the project's JAR file to include a custom manifest with additional attributes.
     * <p>
     * If {@code customManifestOutputJar} is false, replaces the manifest in the existing JAR file in place.
     * If {@code customManifestOutputJar} is true, creates a new JAR file with the custom manifest appended to the filename.
     * If the original JAR does not exist, logs a warning and does nothing.
     *
     * @throws IOException if file operations such as deletion or renaming fail
     */
    private void createJarWithCustomManifest() throws IOException {
        File sourceJar = new File(project.getBuild().getDirectory(), project.getBuild().getFinalName() + ".jar");
        if (!sourceJar.exists()) {
            log().warn("Original JAR file not found: " + sourceJar.getAbsolutePath());
            return;
        }

        Manifest manifest = buildCustomManifest();

        if (!customManifestOutputJar) {
            File tempJar = new File(sourceJar.getParent(), sourceJar.getName() + ".tmp");
            copyJarExcludingManifest(sourceJar, tempJar, manifest);

            if (!sourceJar.delete()) {
                throw new IOException("Could not delete original JAR before renaming temp JAR!");
            }
            if (!tempJar.renameTo(sourceJar)) {
                throw new IOException("Could not rename temp JAR to original name!");
            }
        } else {
            File outJar = new File(project.getBuild().getDirectory(), project.getBuild().getFinalName() + "-with-manifest.jar");
            copyJarExcludingManifest(sourceJar, outJar, manifest);
            log().info("Created JAR with custom manifest: " + outJar.getAbsolutePath());
        }
    }

    /**
     * Creates a new JAR file by copying all entries from the input JAR except the manifest, using the provided manifest for the output.
     *
     * @param inputJar the source JAR file to read entries from
     * @param outputJar the destination JAR file to write entries to
     * @param manifest the manifest to include in the output JAR
     * @throws IOException if an error occurs during file reading or writing
     */
    private void copyJarExcludingManifest(File inputJar, File outputJar, Manifest manifest) throws IOException {
        try (
                JarInputStream jarIn = new JarInputStream(new FileInputStream(inputJar));
                JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(outputJar), manifest)
        ) {
            JarEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = jarIn.getNextJarEntry()) != null) {
                String name = entry.getName();
                if ("META-INF/MANIFEST.MF".equalsIgnoreCase(name)) {
                    continue;
                }
                jarOut.putNextEntry(new JarEntry(name));
                int bytesRead;
                while ((bytesRead = jarIn.read(buffer)) != -1) {
                    jarOut.write(buffer, 0, bytesRead);
                }
                jarOut.closeEntry();
            }
        }
    }

    /**
     * Constructs a JAR manifest enriched with project metadata and custom attributes.
     * <p>
     * Reads the manifest from the project's built JAR file if available, or creates a new manifest if not.
     * Adds custom attributes prefixed with "Netgrif-" containing project information such as name, version, group ID, artifact ID, description, SCM details, license, organization, issue management, build JDK version, build timestamp, and a formatted list of developers.
     * Ensures the manifest version is set to "1.0" if not already present.
     *
     * @return a {@link Manifest} containing both standard and project-specific custom attributes
     * @throws IOException if reading the source JAR file fails
     */
    private Manifest buildCustomManifest() throws IOException {
        File sourceJar = new File(project.getBuild().getDirectory(), project.getBuild().getFinalName() + ".jar");
        Manifest manifest;
        try (JarInputStream jarIn = new JarInputStream(new FileInputStream(sourceJar))) {
            manifest = jarIn.getManifest();
            if (manifest == null) {
                manifest = new Manifest();
            }
        }
        Attributes mainAttrs = manifest.getMainAttributes();
        if (mainAttrs.getValue(Attributes.Name.MANIFEST_VERSION) == null) {
            mainAttrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        }

        List<Developer> developers = project.getDevelopers() != null ? project.getDevelopers() : Collections.emptyList();

        Map<String, String> customAttributes = new LinkedHashMap<>();
        putIfNotNull(customAttributes, "Netgrif-Name", project.getName());
        putIfNotNull(customAttributes, "Netgrif-Version", project.getVersion());
        putIfNotNull(customAttributes, "Netgrif-Url", project.getUrl());
        putIfNotNull(customAttributes, "Netgrif-Description", project.getDescription());
        putIfNotNull(customAttributes, "Netgrif-GroupId", project.getGroupId());
        putIfNotNull(customAttributes, "Netgrif-ArtifactId", project.getArtifactId());

        if (project.getScm() != null) {
            putIfNotNull(customAttributes, "Netgrif-SCM-Connection", project.getScm().getConnection());
            putIfNotNull(customAttributes, "Netgrif-SCM-URL", project.getScm().getUrl());
        }

        if (project.getLicenses() != null && !project.getLicenses().isEmpty()) {
            putIfNotNull(customAttributes, "Netgrif-License", project.getLicenses().getFirst());
        }

        if (project.getOrganization() != null) {
            putIfNotNull(customAttributes, "Netgrif-Organization", project.getOrganization().getName());
            putIfNotNull(customAttributes, "Netgrif-OrganizationUrl", project.getOrganization().getUrl());
        }

        if (project.getIssueManagement() != null) {
            putIfNotNull(customAttributes, "Netgrif-IssueSystem", project.getIssueManagement().getSystem());
            putIfNotNull(customAttributes, "Netgrif-IssueUrl", project.getIssueManagement().getUrl());
        }

        putIfNotNull(customAttributes, "Netgrif-BuildJdk", System.getProperty("java.version"));

        customAttributes.put("Netgrif-BuildTime", java.time.ZonedDateTime.now().toString());

        String authors = developers.stream()
                .map(dev -> {
                    String name = dev.getName();
                    String email = dev.getEmail();
                    String org = dev.getOrganization();
                    if (name == null || name.isBlank()) {
                        return null;
                    }
                    StringBuilder sb = new StringBuilder(name);
                    if (org != null && !org.isBlank()) {
                        sb.append(" (").append(org).append(")");
                    }
                    if (email != null && !email.isBlank()) {
                        sb.append(" <").append(email).append(">");
                    }
                    return sb.toString();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));

        if (!authors.isBlank()) {
            putIfNotNull(customAttributes, "Netgrif-Author", authors);
        }
        customAttributes.forEach(mainAttrs::putValue);

        return manifest;
    }

    /****
     * Inserts the key-value pair into the map if the value is non-null and not blank.
     *
     * The value is converted to a string before insertion. No action is taken if the value is null or blank.
     */
    private void putIfNotNull(Map<String, String> map, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            map.put(key, String.valueOf(value));
        }
    }

    /**
     * Determines the host application artifact to use for dependency exclusion during packaging.
     * <p>
     * Returns a {@link SimpleArtifact} representing the host application based on the configured
     * host application coordinates or a specified NAE version. If neither is provided, logs a warning
     * and returns {@code null}.
     *
     * @return the host application artifact, or {@code null} if not configured
     */
    private SimpleArtifact getHostAppArtifact() {
        SimpleArtifact hostAppArtifact = null;
        if (hostApp == null && (naeVersion == null || naeVersion.isEmpty())) {
            log().warn("Host application was not found. Packaging module without host application");
            return null;
        }
        if (naeVersion != null && !naeVersion.isEmpty()) {
            hostAppArtifact = new SimpleArtifact(Constants.NETGRIF_GROUP_ID, Constants.NAE_ARTIFACT_ID, naeVersion);
        } else if (hostApp.isValid()) {
            hostAppArtifact = hostApp;
        }
        return hostAppArtifact;
    }

    /**
     * Recursively searches a dependency tree for a node matching the specified groupId, artifactId, and version.
     *
     * @param groupId the group ID to match
     * @param artifactId the artifact ID to match
     * @param version the version to match
     * @param node the root node to start the search from
     * @return the matching DependencyNode if found, or null if not present in the tree
     */
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

    /****
     * Returns a flat set of all dependency nodes in the subtree rooted at the given parent node.
     *
     * @param parent the root dependency node to start flattening from; may be null
     * @return a set containing all unique dependency nodes found by recursively traversing the children of the parent node, or an empty set if none
     */
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

    /**
     * Configures the assembly descriptor to produce a ZIP archive containing only the module's unique runtime dependencies,
     * excluding those present in the host application and any additional specified exclusions.
     *
     * @param assembly the assembly descriptor builder to configure
     * @param hostDep the main host dependency to exclude from the package, or null if not applicable
     * @param hostAppDependencies additional host application dependencies to exclude, or null if not applicable
     */
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
        if (log().isDebugEnabled())
            log().debug("Excluding extra dependencies: " + excludes);
        excludes.forEach(depSet::exclude);
    }

    /**
     * Configures the assembly descriptor to package the project's main artifact and its runtime dependencies into a ZIP archive, excluding dependencies present in the host application and any additional specified exclusions.
     *
     * @param assembly the assembly descriptor builder to configure
     * @param hostDep the main host dependency to exclude from the package, or null if not applicable
     * @param hostAppDependencies additional host application dependencies to exclude, or null if not applicable
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
        if (log().isDebugEnabled())
            log().debug("Excluding extra dependencies: " + excludes);
        excludes.forEach(depSet::exclude);
    }

    /**
     * Generates a Spring auto-configuration imports file by scanning the project's build output for classes annotated with
     * {@code @Configuration} or {@code @AutoConfiguration}.
     * <p>
     * Writes the fully qualified names of detected configuration classes to {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
     * in the build output directory. If no such classes are found, no file is created.
     *
     * @throws IOException if directory creation or file writing fails
     */
    private void generateSpringFactoriesToOutputDir() throws IOException {
        String outputDir = project.getBuild().getOutputDirectory();
        File springMetaInf = new File(outputDir, "META-INF/spring");
        if (!springMetaInf.exists()) {
            springMetaInf.mkdirs();
        }
        List<String> configClasses = new ArrayList<>();
        try (ScanResult scanResult = new ClassGraph()
                .overrideClasspath(outputDir)
                .enableAnnotationInfo()
                .enableClassInfo()
                .scan()) {
            for (ClassInfo ci : scanResult.getAllClasses()) {
                if (ci.hasAnnotation("org.springframework.context.annotation.Configuration")
                        || ci.hasAnnotation("org.springframework.boot.autoconfigure.AutoConfiguration")) {
                    configClasses.add(ci.getName());
                }
            }
        }

        if (configClasses.isEmpty()) {
            return;
        }
        Collections.sort(configClasses);

        File importsFile = new File(springMetaInf, "org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        try (PrintWriter writer = new PrintWriter(new FileWriter(importsFile))) {
            for (String className : configClasses) {
                log().info("Adding to imports: " + className);
                writer.println(className);
            }
        }
        log().info("Generated " + importsFile.getAbsolutePath() + " with auto-configuration: " + configClasses.size() + " entries");
    }


    /**
     * Removes specific application and worker property files from the built JAR.
     * <p>
     * Excludes "application.properties", "application.yaml", and "application.yml" from the root or BOOT-INF/classes/ directory.
     * Also removes "application-worker.properties", "application-worker.yaml", and "application-worker.yml" from these locations unless {@code forceEnableWorkerProfile} is true.
     * The method creates a temporary JAR without the excluded files and atomically replaces the original JAR.
     *
     * @throws IOException if an I/O error occurs during file operations or if the JAR cannot be replaced
     */
    private void removeProperties() throws IOException {
        File jarFile = new File(project.getBuild().getDirectory(), project.getBuild().getFinalName() + ".jar");
        if (!jarFile.exists()) {
            log().warn("JAR file not found: " + jarFile.getAbsolutePath());
            return;
        }

        File tempJar = new File(jarFile.getParent(), jarFile.getName() + ".tmp");

        Set<String> removeFiles = Set.of(
                "application.properties",
                "application.yaml",
                "application.yml"
        );
        Set<String> removeWorkerFiles = Set.of(
                "application-worker.properties",
                "application-worker.yaml",
                "application-worker.yml"
        );

        try (JarInputStream jarIn = new JarInputStream(new FileInputStream(jarFile));
             JarOutputStream jarOut = new JarOutputStream(
                     new FileOutputStream(tempJar),
                     jarIn.getManifest() != null ? jarIn.getManifest() : new Manifest())) {

            JarEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = jarIn.getNextJarEntry()) != null) {
                String name = entry.getName();
                String fileName = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;

                boolean shouldRemove = false;

                if (removeFiles.contains(fileName) &&
                        (isRootOrClasspath(name))) {
                    log().info("Removing application property from JAR: " + name);
                    shouldRemove = true;
                }
                if (!forceEnableWorkerProfile &&
                        removeWorkerFiles.contains(fileName) &&
                        (isRootOrClasspath(name))) {
                    log().info("Removing worker property from JAR: " + name);
                    shouldRemove = true;
                }
                if (shouldRemove) {
                    continue;
                }

                jarOut.putNextEntry(new JarEntry(name));
                int bytesRead;
                while ((bytesRead = jarIn.read(buffer)) != -1) {
                    jarOut.write(buffer, 0, bytesRead);
                }
                jarOut.closeEntry();
            }
        }
        if (!jarFile.delete()) {
            throw new IOException("Could not delete original JAR before renaming temp JAR!");
        }
        if (!tempJar.renameTo(jarFile)) {
            throw new IOException("Could not rename temp JAR to original name!");
        }
    }

    /**
     * Determines if the given entry name is located at the root of the archive or under the {@code BOOT-INF/classes/} directory.
     *
     * @param name the entry name to check
     * @return {@code true} if the entry is at the root or within {@code BOOT-INF/classes/}; {@code false} otherwise
     */
    private boolean isRootOrClasspath(String name) {
        return !name.contains("/") ||
                name.startsWith("BOOT-INF/classes/");
    }

}
