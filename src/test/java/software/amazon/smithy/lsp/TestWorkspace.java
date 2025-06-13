/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NodeMapper;

/**
 * Sets up a temporary directory containing a Smithy project
 */
public final class TestWorkspace {
    private static final NodeMapper MAPPER = new NodeMapper();
    private final Path root;
    private SmithyBuildConfig config;
    private final String name;

    private TestWorkspace(Path root, SmithyBuildConfig config) {
        this.root = root;
        this.config = config;
        this.name = root.toString();
    }

    /**
     * @return The path of the workspace root
     */
    public Path getRoot() {
        return root;
    }

    public SmithyBuildConfig getConfig() {
        return config;
    }

    public String getName() {
        return name;
    }

    /**
     * @param filename The name of the file to get the URI for, relative to the root
     * @return The LSP URI for the given filename
     */
    public String getUri(String filename) {
        return this.root.resolve(filename).toUri().toString();
    }

    /**
     * @param relativePath The path where the model will be added, relative to the root
     * @param model The text of the model to add
     */
    public void addModel(String relativePath, String model) {
        try {
            Files.writeString(root.resolve(relativePath), model);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void moveModel(String currentPath, String toPath) {
        try {
            Files.move(root.resolve(currentPath), root.resolve(toPath));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void deleteModel(String relativePath) {
        try {
            Files.delete(root.resolve(relativePath));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void updateConfig(SmithyBuildConfig newConfig) {
        writeConfig(root, newConfig);
        this.config = newConfig;
    }

    public String readFile(String relativePath) {
        try {
            return Files.readString(root.resolve(relativePath));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @param model String of the model to create in the workspace
     * @return A workspace with a single model, "main.smithy", with the given contents, and
     *  a smithy-build.json with sources = ["main.smithy"]
     */
    public static TestWorkspace singleModel(String model) {
        return builder()
                .withSourceFile("main.smithy", model)
                .build();
    }

    /**
     * @return A workspace with no models, and a smithy-build.json with sources = ["model/"]
     */
    public static TestWorkspace emptyWithDirSource() {
        return builder()
                .withSourceDir(new Dir().withPath("model"))
                .build();
    }

    /**
     * @param models Strings of the models to create in the workspace
     * @return A workspace with n models, each "model-n.smithy", with their given contents,
     *  and a smithy-build.json with sources = ["model-0.smithy", ..., "model-n.smithy"]
     */
    public static TestWorkspace multipleModels(String... models) {
        Builder builder = builder();
        for (int i = 0; i < models.length; i++) {
            builder.withSourceFile("model-" + i + ".smithy", models[i]);
        }
        return builder.build();
    }

    public static TestWorkspace emptyWithNoConfig(String prefix) {
        Path root;
        try {
            root = Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new TestWorkspace(root, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Dir dir() {
        return new Dir();
    }

    public static class Dir {
        String path;
        Map<String, String> sourceModels = new HashMap<>();
        Map<String, String> importModels = new HashMap<>();
        List<Dir> sourceDirs = new ArrayList<>();
        List<Dir> importDirs = new ArrayList<>();

        public Dir withPath(String path) {
            this.path = path;
            return this;
        }

        public Dir withSourceFile(String filename, String model) {
            this.sourceModels.put(filename, model);
            return this;
        }

        public Dir withImportFile(String filename, String model) {
            this.importModels.put(filename, model);
            return this;
        }

        public Dir withSourceDir(Dir dir) {
            this.sourceDirs.add(dir);
            return this;
        }

        public Dir withImportDir(Dir dir) {
            this.importDirs.add(dir);
            return this;
        }

        protected void writeModels(Path toDir) {
            try {
                if (!Files.exists(toDir)) {
                    Files.createDirectory(toDir);
                }
                writeModels(toDir, sourceModels);
                writeModels(toDir, importModels);
                sourceDirs.forEach(d -> d.writeModels(toDir.resolve(d.path)));
                importDirs.forEach(d -> d.writeModels(toDir.resolve(d.path)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static void writeModels(Path toDir, Map<String, String> models) throws Exception {
            for (Map.Entry<String, String> entry : models.entrySet()) {
                Files.writeString(toDir.resolve(entry.getKey()), entry.getValue());
            }
        }
    }

    public static final class Builder extends Dir {
        private SmithyBuildConfig config = null;
        private Path root = null;

        private Builder() {}

        @Override
        public Builder withPath(String path) {
            this.path = path;
            return this;
        }

        @Override
        public Builder withSourceFile(String filename, String model) {
            super.withSourceFile(filename, model);
            return this;
        }

        @Override
        public Builder withImportFile(String filename, String model) {
            super.withImportFile(filename, model);
            return this;
        }

        @Override
        public Builder withSourceDir(Dir dir) {
            super.withSourceDir(dir);
            return this;
        }

        @Override
        public Builder withImportDir(Dir dir) {
            super.withImportDir(dir);
            return this;
        }

        public Builder withConfig(SmithyBuildConfig config) {
            this.config = config;
            return this;
        }

        public Builder withRoot(Path root) {
            this.root = root;
            return this;
        }

        public TestWorkspace build() {
            try {
                if (path == null) {
                    path = "test";
                }
                Path projectRoot;
                if (this.root != null) {
                    projectRoot = Files.createDirectory(this.root.resolve(path));
                } else {
                    projectRoot = Files.createTempDirectory(path);
                    projectRoot.toFile().deleteOnExit();
                }

                List<String> sources = new ArrayList<>();
                sources.addAll(sourceModels.keySet());
                sources.addAll(sourceDirs.stream().map(d -> d.path).toList());

                List<String> imports = new ArrayList<>();
                imports.addAll(importModels.keySet());
                imports.addAll(importDirs.stream().map(d -> d.path).toList());

                if (config == null) {
                    config = SmithyBuildConfig.builder()
                            .version("1")
                            .sources(sources)
                            .imports(imports)
                            .build();
                }
                writeConfig(projectRoot, config);

                writeModels(projectRoot);

                return new TestWorkspace(projectRoot, config);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void writeConfig(Path root, SmithyBuildConfig config) {
        String configString = Node.prettyPrintJson(MAPPER.serialize(config));
        Path configPath = root.resolve("smithy-build.json");
        try {
            Files.writeString(configPath, configString);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
