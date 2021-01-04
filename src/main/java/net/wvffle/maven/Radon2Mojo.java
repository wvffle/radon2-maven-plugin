package net.wvffle.maven;

import com.amihaiemil.eoyaml.*;
import com.amihaiemil.eoyaml.extensions.MergedYamlMapping;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ExcludesArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.CollectingDependencyNodeVisitor;

import java.io.*;
import java.nio.file.AccessDeniedException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Mojo(name = "radon2", defaultPhase = LifecyclePhase.COMPILE)
public class Radon2Mojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    MavenProject project;

    @Parameter(required = true, readonly = true)
    String radonPath;

    @Parameter(defaultValue = "/tmp/radon2-maven-plugin", required = true, readonly = true)
    String workPath;

    @Parameter(defaultValue = "radon.yml", required = true, readonly = true)
    String configPath;

    @Parameter(defaultValue = "${project.build.finalName}.jar", required = true, readonly = true)
    String output;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    MavenSession session;

    @Component(hint="default")
    private DependencyGraphBuilder dependencyGraphBuilder;

    public void execute() throws MojoExecutionException, MojoFailureException {
        File inputFile = new File(String.format("%s/target/%s.jar", project.getBasedir(), project.getBuild().getFinalName()));
        File radonConfigFile = new File(configPath);

        if (!inputFile.exists()) {
            System.err.println("target jar not found");
            throw new MojoFailureException("target jar not found");
        }

        File newInputFile = new File(String.format("%s/target/unobfuscated-%s.jar", project.getBasedir(), project.getBuild().getFinalName()));
        inputFile.renameTo(newInputFile);

        if (!radonConfigFile.exists()) {
            System.err.println("radon.yml not found");
            throw new MojoFailureException("radon.yml not found");
        }

        List<String> libraries = new ArrayList<>();

        File workDir = new File(workPath);
        if (!workDir.exists()) {
            workDir.mkdirs();
        }

        String javaHome = System.getProperty("java.home");
        File jrtFile = new File(String.format("%s/lib/rt.jar", javaHome));

        if (!jrtFile.exists()) {
            File rt = new File(workDir, "rt.jar");
            try {
                JRTExtractor.main(jrtFile);
                jrtFile = rt;
            } catch (Throwable e) {
                if (e instanceof AccessDeniedException) {
                    System.err.println("rt.jar is unaccessible or non-existant");
                } else {
                    System.err.println(e.getMessage());
                }

                throw new MojoFailureException(e.getMessage(), e);
            }
        }

        libraries.add(jrtFile.getAbsolutePath());
        libraries.add(String.format("%s/lib/jce.jar", javaHome));

        System.out.println("Collecting artifacts");
        ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        buildingRequest.setProject(project);

        Set<Artifact> artifacts = new HashSet<>();
        try {
            DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, new ExcludesArtifactFilter(new ArrayList<>()));
            CollectingDependencyNodeVisitor visitor = new  CollectingDependencyNodeVisitor();
            rootNode.accept(visitor);
            List<DependencyNode> children = visitor.getNodes();

            for(DependencyNode node : children) {
                artifacts.add(node.getArtifact());
            }
        } catch (DependencyGraphBuilderException e) {
            System.err.println(e.getMessage());
            throw new MojoFailureException(e.getMessage(), e);
        }

        System.out.println("Number of artifacts: " + artifacts.size());
        for (Artifact artifact : artifacts) {
            String path = artifact.getFile().getAbsolutePath();
            System.out.printf("- %s%n", path);
            libraries.add(path);
        }

        File workingConfigFile = new File(workDir, "radon.yml");

        try {
            YamlMapping config = Yaml.createYamlInput(radonConfigFile).readYamlMapping();

            YamlSequenceBuilder yamlLibrariesBuilder = Yaml.createYamlSequenceBuilder();
            for (String lib : libraries) {
                yamlLibrariesBuilder = yamlLibrariesBuilder.add(lib);
            }

            YamlMapping newConfig = Yaml.createYamlMappingBuilder()
                    .add("input", newInputFile.getAbsolutePath())
                    .add("output", new File(inputFile.getParentFile(), output).getAbsolutePath())
                    .add("libraries", yamlLibrariesBuilder.build())
                    .build();

            YamlMapping generated = new MergedYamlMapping(newConfig, () -> config);
            System.out.println("Generated config:");
            System.out.println(generated.toString());

            YamlPrinter printer = Yaml.createYamlPrinter(new FileWriter(workingConfigFile));
            printer.print(generated);
        } catch (IOException e) {
            System.err.println(e.getMessage());
            throw new MojoFailureException(e.getMessage(), e);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    String.format("%s/bin/java", javaHome),
                    "-jar",
                    radonPath,
                    "--config",
                    workingConfigFile.getAbsolutePath()
            );
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            Process proc = pb.start();
            proc.waitFor();

            String error = new BufferedReader(new InputStreamReader(proc.getErrorStream()))
                    .lines()
                    .collect(Collectors.joining("\n"));

            System.err.println(error);

            if (error.contains("Exception")) {
                throw new MojoFailureException("Radon exception");
            }
        } catch (IOException | InterruptedException e) {
            System.err.println(e.getMessage());
            throw new MojoFailureException(e.getMessage(), e);
        }
    }
}
