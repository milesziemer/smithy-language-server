/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.DocumentFilter;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.TextDocumentChangeRegistrationOptions;
import org.eclipse.lsp4j.TextDocumentRegistrationOptions;
import org.eclipse.lsp4j.TextDocumentSaveRegistrationOptions;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.WatchKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.lsp.project.Project;

/**
 * Handles computing the {@link Registration}s and {@link Unregistration}s for
 * that tell the client which files and directories to watch for changes
 *
 * <p>The server needs to know when files are added or removed from the project's
 * sources or imports. Instead of watching the client's file system, we tell the
 * client to send us notifications when these events occur, so we can reload the
 * project.
 *
 * <p>Clients don't de-duplicate file watchers, so we have to unregister all
 * file watchers before sending a new list to watch, or keep track of them to make
 * more granular changes. The current behavior is to just unregister and re-register
 * everything, since these events should be rarer. But we can optimize it in the
 * future.
 */
final class FileRegistrations {
    private static final Integer WATCH_FILE_KIND = WatchKind.Delete | WatchKind.Create;
    private static final String WATCH_BUILD_FILES_ID = "WatchSmithyBuildFiles";
    private static final String WATCH_SMITHY_FILES_ID = "WatchSmithyFiles";
    private static final String WATCH_FILES_METHOD = "workspace/didChangeWatchedFiles";
    private static final List<Unregistration> SMITHY_FILE_WATCHER_UNREGISTRATIONS = List.of(new Unregistration(
            WATCH_SMITHY_FILES_ID,
            WATCH_FILES_METHOD));
    private static final List<Unregistration> BUILD_FILE_WATCHER_UNREGISTRATIONS = List.of(new Unregistration(
            WATCH_BUILD_FILES_ID,
            WATCH_FILES_METHOD));
    private static final List<Registration> DOCUMENT_SYNC_REGISTRATIONS;

    static {
        List<DocumentFilter> buildDocumentSelector = List.of(
                new DocumentFilter("json", "file", "**/{smithy-build,.smithy-project}.json"));

        // Sync on changes/save/open/close for any build file
        var openCloseBuildOpts = new TextDocumentRegistrationOptions(buildDocumentSelector);
        var changeBuildOpts = new TextDocumentChangeRegistrationOptions(TextDocumentSyncKind.Incremental);
        changeBuildOpts.setDocumentSelector(buildDocumentSelector);
        var saveBuildOpts = new TextDocumentSaveRegistrationOptions();
        saveBuildOpts.setDocumentSelector(buildDocumentSelector);

        DocumentFilter smithyFilter = new DocumentFilter();
        smithyFilter.setLanguage("smithy");
        smithyFilter.setScheme("file");
        List<DocumentFilter> smithyDocumentSelector = List.of(smithyFilter);

        DocumentFilter smithyJarFilter = new DocumentFilter();
        smithyJarFilter.setLanguage("smithy");
        smithyJarFilter.setScheme("smithyjar");
        List<DocumentFilter> smithyAndSmithyJarDocumentSelector = List.of(smithyFilter, smithyJarFilter);

        // Sync on open/close for smithy and smithyjar files (which are readonly)
        var openCloseSmithyOpts = new TextDocumentRegistrationOptions(smithyAndSmithyJarDocumentSelector);
        // Sync on change/save for smithy files
        var changeSmithyOpts = new TextDocumentChangeRegistrationOptions(TextDocumentSyncKind.Incremental);
        changeSmithyOpts.setDocumentSelector(smithyDocumentSelector);
        var saveSmithyOpts = new TextDocumentSaveRegistrationOptions();
        saveSmithyOpts.setDocumentSelector(smithyDocumentSelector);

        DOCUMENT_SYNC_REGISTRATIONS = List.of(
                new Registration("SyncSmithyFiles/Open", "textDocument/didOpen", openCloseSmithyOpts),
                new Registration("SyncSmithyFiles/Close", "textDocument/didClose", openCloseSmithyOpts),
                new Registration("SyncSmithyFiles/Change", "textDocument/didChange", changeSmithyOpts),
                new Registration("SyncSmithyFiles/Save", "textDocument/didSave", saveBuildOpts),
                new Registration("SyncSmithyBuildFiles/Open", "textDocument/didOpen", openCloseBuildOpts),
                new Registration("SyncSmithyBuildFiles/Close", "textDocument/didClose", openCloseBuildOpts),
                new Registration("SyncSmithyBuildFiles/Change", "textDocument/didChange", changeBuildOpts),
                new Registration("SyncSmithyBuildFiles/Save", "textDocument/didSave", saveBuildOpts));
    }

    private FileRegistrations() {
    }

    static List<Registration> getDocumentSyncRegistrations() {
        return DOCUMENT_SYNC_REGISTRATIONS;
    }

    /**
     * Creates registrations to tell the client to watch for new or deleted
     * Smithy files, specifically for files that are part of {@link Project}s.
     *
     * @param projects The projects to get registrations for
     * @return The registrations to watch for Smithy file changes across all projects
     */
    static List<Registration> getSmithyFileWatcherRegistrations(Collection<Project> projects) {
        List<FileSystemWatcher> smithyFileWatchers = projects.stream()
                .flatMap(project -> FilePatterns.getSmithyFileWatchPatterns(project).stream())
                .map(pattern -> new FileSystemWatcher(Either.forLeft(pattern), WATCH_FILE_KIND))
                .toList();

        return Collections.singletonList(new Registration(
                WATCH_SMITHY_FILES_ID,
                WATCH_FILES_METHOD,
                new DidChangeWatchedFilesRegistrationOptions(smithyFileWatchers)));
    }

    /**
     * @return The unregistrations to stop watching for Smithy file changes
     */
    static List<Unregistration> getSmithyFileWatcherUnregistrations() {
        return SMITHY_FILE_WATCHER_UNREGISTRATIONS;
    }

    /**
     * Creates registrations to tell the client to watch for any build file
     * creations or deletions, across all workspaces.
     *
     * @param workspaceRoots The roots of the workspaces to get registrations for
     * @return The registrations to watch for build file changes across all workspaces
     */
    static List<Registration> getBuildFileWatcherRegistrations(Collection<Path> workspaceRoots) {
        List<FileSystemWatcher> watchers = workspaceRoots.stream()
                .map(FilePatterns::getWorkspaceBuildFilesWatchPattern)
                .map(pattern -> new FileSystemWatcher(Either.forLeft(pattern), WATCH_FILE_KIND))
                .toList();

        return Collections.singletonList(new Registration(
                WATCH_BUILD_FILES_ID,
                WATCH_FILES_METHOD,
                new DidChangeWatchedFilesRegistrationOptions(watchers)));
    }

    /**
     * @return The unregistrations to stop watching for build file changes
     */
    static List<Unregistration> getBuildFileWatcherUnregistrations() {
        return BUILD_FILE_WATCHER_UNREGISTRATIONS;
    }
}
