package me.xemor.shuffler;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class DependencyLoader implements PluginLoader {
    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        // Check if Jackson is already loaded
        boolean jacksonAlreadyLoaded = false;
        try {
            Class.forName("com.fasterxml.jackson.dataformat.yaml.YAMLFactory");
            jacksonAlreadyLoaded = true;
        } catch (ClassNotFoundException ignored) {
            // Dependencies not loaded
        }

        // Only load Jackson if Jackson isn't already loaded
        if (!jacksonAlreadyLoaded) {
            MavenLibraryResolver resolver = new MavenLibraryResolver();
            List<String> mavenCentralDependencies =
                    List.of(
                            "com.fasterxml.jackson.core:jackson-databind:2.18.2",
                            "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.7.0"
                    );
            for (String dependency : mavenCentralDependencies) {
                resolver.addDependency(new Dependency(new DefaultArtifact(dependency), null));
            }

            resolver.addRepository(new RemoteRepository.Builder("central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());

            classpathBuilder.addLibrary(resolver);
        }
    }
}
