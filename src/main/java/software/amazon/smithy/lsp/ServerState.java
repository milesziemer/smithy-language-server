/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.eclipse.lsp4j.WorkspaceFolder;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectAndFile;
import software.amazon.smithy.lsp.project.ProjectFile;
import software.amazon.smithy.lsp.project.ProjectLoader;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.util.Result;

/**
 * Keeps track of the state of the server.
 */
public final class ServerState {
    private static final Logger LOGGER = Logger.getLogger(ServerState.class.getName());

    private final Map<String, Project> detachedProjects;
    private final Map<String, Project> attachedProjects;
    private final Set<Path> workspacePaths;
    private final Set<String> managedUris;
    private final DocumentLifecycleManager lifecycleManager;

    /**
     * Create a new, empty server state.
     */
    public ServerState() {
        this.detachedProjects = new HashMap<>();
        this.attachedProjects = new HashMap<>();
        this.workspacePaths = new HashSet<>();
        this.managedUris = new HashSet<>();
        this.lifecycleManager = new DocumentLifecycleManager();
    }

    public Collection<Project> getAllProjects() {
        List<Project> allProjects = new ArrayList<>(attachedProjects.values());
        allProjects.addAll(detachedProjects.values());
        return allProjects;
    }

    public Collection<ProjectAndFile> getAllManaged() {
        List<ProjectAndFile> allManaged = new ArrayList<>(managedUris.size());
        for (String uri : managedUris) {
            allManaged.add(findManaged(uri));
        }
        return allManaged;
    }

    public Set<Path> workspacePaths() {
        return workspacePaths;
    }

    public DocumentLifecycleManager lifecycleManager() {
        return lifecycleManager;
    }

    public Project findProjectByRoot(String root) {
        Project attached = attachedProjects.get(root);
        if (attached == null) {
            return detachedProjects.get(root);
        }
        return attached;
    }

    /**
     * @param uri Uri of the document to get
     * @return The document if found and it is managed, otherwise {@code null}
     */
    public Document getManagedDocument(String uri) {
        if (managedUris.contains(uri)) {
            ProjectAndFile projectAndFile = findProjectAndFile(uri);
            if (projectAndFile != null) {
                return projectAndFile.file().document();
            }
        }

        return null;
    }

    /**
     * @param path The path of the document to get
     * @return The document if found and it is managed, otherwise {@code null}
     */
    public Document getManagedDocument(Path path) {
        if (managedUris.isEmpty()) {
            return null;
        }

        String uri = LspAdapter.toUri(path.toString());
        return getManagedDocument(uri);
    }

    ProjectAndFile findProjectAndFile(String uri) {
        ProjectAndFile attached = findAttachedAndRemoveDetached(uri);
        if (attached != null) {
            return attached;
        }

        Project detachedProject = detachedProjects.get(uri);
        if (detachedProject != null) {
            String path = LspAdapter.toPath(uri);
            ProjectFile projectFile = detachedProject.getProjectFile(path);
            if (projectFile != null) {
                return new ProjectAndFile(uri, detachedProject, projectFile, true);
            }
        }

        LOGGER.warning(() -> "Tried to unknown file: " + uri);

        return null;
    }

    ProjectAndFile findManaged(String uri) {
        if (managedUris.contains(uri)) {
            return findProjectAndFile(uri);
        }
        return null;
    }

    ProjectAndFile open(String uri, String text) {
        managedUris.add(uri);

        ProjectAndFile projectAndFile = findProjectAndFile(uri);
        if (projectAndFile != null) {
            projectAndFile.file().document().applyEdit(null, text);
        } else {
            createDetachedProject(uri, text);
            projectAndFile = findProjectAndFile(uri); // Note: This will always be present
        }

        return projectAndFile;
    }

    void close(String uri) {
        managedUris.remove(uri);

        ProjectAndFile projectAndFile = findProjectAndFile(uri);
        if (projectAndFile != null && projectAndFile.project().type() == Project.Type.DETACHED) {
            // Only cancel tasks for detached projects, since we're dropping the project
            lifecycleManager.cancelTask(uri);
            detachedProjects.remove(uri);
        }
    }

    /**
     * Searches for the given {@code uri} in attached projects, and if found,
     * makes sure any old detached projects for that file are removed.
     *
     * @param uri The uri of the project and file to find
     * @return The attached project and file, or null if not found
     */
    private ProjectAndFile findAttachedAndRemoveDetached(String uri) {
        String path = LspAdapter.toPath(uri);
        // We might be in a state where a file was added to a tracked project,
        // but was opened before the project loaded. This would result in it
        // being placed in a detachedProjects project. Removing it here is basically
        // like removing it lazily, although it does feel a little hacky.
        for (Project project : attachedProjects.values()) {
            ProjectFile projectFile = project.getProjectFile(path);
            if (projectFile != null) {
                detachedProjects.remove(uri);
                return new ProjectAndFile(uri, project, projectFile, false);
            }
        }

        return null;
    }

    void createDetachedProject(String uri, String text) {
        Project project = ProjectLoader.loadDetached(uri, text);
        detachedProjects.put(uri, project);
    }

    List<Exception> tryInitProject(Path root) {
        LOGGER.finest("Initializing project at " + root);
        lifecycleManager.cancelAllTasks();

        Result<Project, List<Exception>> loadResult = ProjectLoader.load(root, this);
        String projectName = root.toString();
        if (loadResult.isOk()) {
            Project updatedProject = loadResult.unwrap();

            // If the project didn't load any config files, it is now empty and should be removed
            if (updatedProject.config().buildFiles().isEmpty()) {
                removeProjectAndResolveDetached(projectName);
            } else {
                resolveDetachedProjects(attachedProjects.get(projectName), updatedProject);
                attachedProjects.put(projectName, updatedProject);
            }

            LOGGER.finest("Initialized project at " + root);
            return List.of();
        }

        LOGGER.severe("Init project failed");

        // TODO: Maybe we just start with this anyways by default, and then add to it
        //  if we find a smithy-build.json, etc.
        // If we overwrite an existing project with an empty one, we lose track of the state of tracked
        // files. Instead, we will just keep the original project before the reload failure.
        attachedProjects.computeIfAbsent(projectName, ignored -> Project.empty(root));

        return loadResult.unwrapErr();
    }

    void loadWorkspace(WorkspaceFolder workspaceFolder) {
        Path workspaceRoot = Paths.get(URI.create(workspaceFolder.getUri()));
        workspacePaths.add(workspaceRoot);
        try {
            List<Path> projectRoots = ProjectRootVisitor.findProjectRoots(workspaceRoot);
            for (Path root : projectRoots) {
                tryInitProject(root);
            }
        } catch (IOException e) {
            LOGGER.severe(e.getMessage());
        }
    }

    void removeWorkspace(WorkspaceFolder folder) {
        Path workspaceRoot = Paths.get(URI.create(folder.getUri()));
        workspacePaths.remove(workspaceRoot);

        // Have to do the removal separately, so we don't modify project.attachedProjects()
        // while iterating through it
        List<String> projectsToRemove = new ArrayList<>();
        for (var entry : attachedProjects.entrySet()) {
            if (entry.getValue().root().startsWith(workspaceRoot)) {
                projectsToRemove.add(entry.getKey());
            }
        }

        for (String projectName : projectsToRemove) {
            removeProjectAndResolveDetached(projectName);
        }
    }

    private void removeProjectAndResolveDetached(String projectName) {
        Project removedProject = attachedProjects.remove(projectName);
        if (removedProject != null) {
            resolveDetachedProjects(removedProject, Project.empty(removedProject.root()));
        }
    }

    private void resolveDetachedProjects(Project oldProject, Project updatedProject) {
        // This is a project reload, so we need to resolve any added/removed files
        // that need to be moved to or from detachedProjects projects.
        if (oldProject != null) {
            Set<String> currentProjectSmithyPaths = oldProject.smithyFiles().keySet();
            Set<String> updatedProjectSmithyPaths = updatedProject.smithyFiles().keySet();

            Set<String> addedPaths = new HashSet<>(updatedProjectSmithyPaths);
            addedPaths.removeAll(currentProjectSmithyPaths);
            for (String addedPath : addedPaths) {
                String addedUri = LspAdapter.toUri(addedPath);
                detachedProjects.remove(addedUri);
            }

            Set<String> removedPaths = new HashSet<>(currentProjectSmithyPaths);
            removedPaths.removeAll(updatedProjectSmithyPaths);
            for (String removedPath : removedPaths) {
                String removedUri = LspAdapter.toUri(removedPath);
                // Only move to a detachedProjects project if the file is managed
                if (managedUris.contains(removedUri)) {
                    Document removedDocument = oldProject.getDocument(removedUri);
                    // The copy here is technically unnecessary, if we make ModelAssembler support borrowed strings
                    createDetachedProject(removedUri, removedDocument.copyText());
                }
            }
        }
    }
}
