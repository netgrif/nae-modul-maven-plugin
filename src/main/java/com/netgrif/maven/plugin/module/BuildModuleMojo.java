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
     * Retrieves the logging instance for the current context.
     *
     * @return the logging instance
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
     * Creates a JAR file by either modifying the existing JAR's manifest or generating a new JAR file with a custom manifest.
     * This method enables the addition of custom attributes to the manifest file without disrupting the rest of the JAR contents.
     * <p>
     * Steps:
     * 1. Locates the source JAR file in the project's build directory.
     * 2. Builds a custom manifest with additional attributes.
     * 3. Depending on the `customManifestOutputJar` flag:
     * - If false: Modifies the existing JAR file, replacing the manifest.
     * - If true: Creates a new JAR file with a custom manifest.
     * <p>
     * If the original JAR file is not found, logs a warning and exits the operation.
     * When modifying the existing JAR file, the method ensures that the temporary JAR is renamed back to the original name.
     * If file operations (e.g., delete or rename) fail, an {@link IOException} is thrown.
     *
     * @throws IOException if an error occurs during file handling or modifications.
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
     * Copies the contents of a JAR file to a new JAR file, excluding the MANIFEST file.
     * This method allows for the creation of a new JAR file with the same entries as the original,
     * excluding the original manifest file while using the provided manifest.
     *
     * @param inputJar  the original JAR file to copy from
     * @param outputJar the new JAR file to be created
     * @param manifest  the custom manifest to be used for the new JAR file
     * @throws IOException if an I/O error occurs during the copying process
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
     * Builds a custom manifest object by either reading the manifest from an existing source JAR file
     * or creating a new manifest if none exists. It then enriches the manifest with custom attributes
     * derived from the project metadata, such as name, version, URLs, description, developers, and other details.
     * <p>
     * The custom attributes are dynamically populated from project fields and include metadata such as:
     * - Project name, version, group ID, artifact ID, and description.
     * - Source control details (SCM connection and URL).
     * - Licensing and organization information.
     * - Build details such as the JDK version and build timestamp.
     * - A combined list of developers associated with the project, including their names, emails, and organizations.
     * <p>
     * This method uses a default manifest version of "1.0" if no version is specified.
     *
     * @return a fully constructed {@link Manifest} object augmented with project-specific custom attributes
     * @throws IOException if an error occurs while reading the source JAR file or during file operations
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

    /**
     * Adds a key-value pair to the specified map if the value is not null
     * and not blank. The value is converted to a string before insertion.
     *
     * @param map   the map to which the key-value pair will be added, must not be null
     * @param key   the key to be added to the map, must not be null
     * @param value the value to associate with the key, added only if non-null and non-blank
     */
    private void putIfNotNull(Map<String, String> map, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            map.put(key, String.valueOf(value));
        }
    }

    /**
     * Retrieves the host application artifact based on the provided configuration.
     * If the host application is not found and no specific version information is provided,
     * a warning is logged, and the method returns null. Otherwise, it attempts to create
     * a {@link SimpleArtifact} instance either using the provided version information or
     * by validating the host application.
     *
     * @return the {@link SimpleArtifact} representing the host application artifact,
     * or null if both the host application and version information are unavailable or invalid.
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
     * Searches for a specific dependency within a dependency tree based on the specified groupId,
     * artifactId, and version. The method performs a recursive search through the dependency node
     * hierarchy and returns the node that matches the provided details.
     *
     * @param groupId    the group ID of the dependency to search for, must not be null
     * @param artifactId the artifact ID of the dependency to search for, must not be null
     * @param version    the version of the dependency to search for, must not be null
     * @param node       the root dependency node to begin the search from, must not be null
     * @return the {@link DependencyNode} containing the matching dependency if found;
     * otherwise, returns null if no match is found within the dependency tree
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

    /**
     * Recursively flattens a hierarchical dependency structure starting from the given parent node
     * into a flat set of dependency nodes.
     *
     * @param parent the root {@link DependencyNode} from which dependencies are recursively flattened.
     *               Can be null, in which case an empty set is returned.
     * @return a {@link Set} of {@link DependencyNode} containing all unique dependencies in the
     * hierarchy starting from the parent node. If the parent is null or has no children,
     * an empty set is returned.
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
     * Configures an assembly descriptor to generate a packaged artifact with the specified properties
     * and custom dependency exclusions. This method customizes the assembly builder by setting the
     * output format, dependency sets, with specific exclusions defined for host dependencies and
     * additional application dependencies.
     *
     * @param assembly            the {@link AssemblyDescriptorBuilder} used to configure the assembly descriptor
     * @param hostDep             the {@link DependencyNode} representing the main host dependency to exclude, can be null
     * @param hostAppDependencies the {@link Set} of {@link DependencyNode} representing additional host application dependencies to exclude, can be null
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
        if (log().isDebugEnabled())
            log().debug("Excluding extra dependencies: " + excludes);
        excludes.forEach(depSet::exclude);
    }

    /**
     * Scans the project's build output directory for classes annotated with Spring configuration annotations
     * (such as {@code @Configuration} or {@code @AutoConfiguration}) and generates a Spring factories file
     * containing these configuration class names.
     * <p>
     * The method performs the following steps:
     * 1. Identifies the build output directory for the current project.
     * 2. Ensures the necessary `META-INF/spring` directory structure exists.
     * 3. Scans the output directory for classes annotated with Spring configuration annotations.
     * 4. Collects and sorts the names of detected configuration classes.
     * 5. Writes these class names into a file named `org.springframework.boot.autoconfigure.AutoConfiguration.imports`
     * within the `META-INF/spring` directory.
     * <p>
     * If no configuration classes are found, the method exits without creating any files. For each detected
     * configuration class, logging information is provided to indicate the inclusion of the class in the
     * generated imports file.
     *
     * @throws IOException if any file operations fail during the process, such as directory creation or file writing.
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
     * Removes specific property files from a JAR file by creating a temporary JAR
     * with the excluded files and replacing the original JAR.
     * <p>
     * This method removes property files matching predefined sets of filenames
     * ("application.properties", "application.yaml", "application.yml") and
     * ("application-worker.properties", "application-worker.yaml",
     * "application-worker.yml") under specific conditions.
     * <p>
     * If the `forceEnableWorkerProfile` field is false, worker-specific property
     * files are also removed.
     * <p>
     * The method verifies the existence of the target JAR file and logs warnings
     * if not found. It performs this operation safely by using a temporary JAR for
     * modifications and replacing the original only after successful processing.
     * <p>
     * If the original JAR cannot be deleted or the temporary JAR cannot be renamed,
     * an IOException is thrown.
     *
     * @throws IOException if an I/O error occurs during file operations or if
     *                     renaming the temporary JAR fails
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
             JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(tempJar), jarIn.getManifest())) {
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

    private boolean isRootOrClasspath(String name) {
        return !name.contains("/") ||
                name.startsWith("BOOT-INF/classes/");
    }

}
