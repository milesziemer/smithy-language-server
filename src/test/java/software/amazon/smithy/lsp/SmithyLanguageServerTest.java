package software.amazon.smithy.lsp;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static software.amazon.smithy.lsp.LspMatchers.diagnosticWithMessage;
import static software.amazon.smithy.lsp.LspMatchers.hasLabel;
import static software.amazon.smithy.lsp.LspMatchers.hasText;
import static software.amazon.smithy.lsp.LspMatchers.makesEditedDocument;
import static software.amazon.smithy.lsp.SmithyMatchers.eventWithMessage;
import static software.amazon.smithy.lsp.SmithyMatchers.hasShapeWithId;
import static software.amazon.smithy.lsp.SmithyMatchers.hasValue;
import static software.amazon.smithy.lsp.UtilMatchers.anOptionalOf;
import static software.amazon.smithy.lsp.document.DocumentTest.safeString;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.model.MavenConfig;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.lsp.diagnostics.SmithyDiagnostics;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.ext.SelectorParams;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectAndFile;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.protocol.RangeBuilder;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.LengthTrait;
import software.amazon.smithy.model.validation.Severity;

public class SmithyLanguageServerTest {
    @Test
    public void runsSelector() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                string Foo
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        SelectorParams params = new SelectorParams("string");
        List<? extends Location> locations = server.selectorCommand(params).get();

        assertThat(locations, not(empty()));
    }

    @Test
    public void formatting() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                structure Foo{
                bar:    Baz}

                @tags(
                ["a",
                    "b"])
                string Baz
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(model)
                .build());

        TextDocumentIdentifier id = new TextDocumentIdentifier(uri);
        DocumentFormattingParams params = new DocumentFormattingParams(id, new FormattingOptions());
        List<? extends TextEdit> edits = server.formatting(params).get();

        Document document = server.getState().getManagedDocument(uri);
        assertThat(edits, containsInAnyOrder(makesEditedDocument(document, safeString("""
                $version: "2"

                namespace com.foo

                structure Foo {
                    bar: Baz
                }

                @tags(["a", "b"])
                string Baz
                """))));
    }

    @Test
    public void didChange() throws Exception {
        String model = safeString("""
                $version: "2"

                namespace com.foo

                structure GetFooInput {
                }

                operation GetFoo {
                }
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        DidOpenTextDocumentParams openParams = new RequestBuilders.DidOpen()
                .uri(uri)
                .text(model)
                .build();
        server.didOpen(openParams);

        RangeBuilder rangeBuilder = new RangeBuilder()
                .startLine(7)
                .startCharacter(18)
                .endLine(7)
                .endCharacter(18);
        RequestBuilders.DidChange changeBuilder = new RequestBuilders.DidChange().uri(uri);

        // Add new line and leading spaces
        server.didChange(changeBuilder.range(rangeBuilder.build()).text(safeString("\n    ")).build());
        // add 'input: G'
        server.didChange(changeBuilder.range(rangeBuilder.shiftNewLine().shiftRight(4).build()).text("i").build());
        server.didChange(changeBuilder.range(rangeBuilder.shiftRight().build()).text("n").build());
        server.didChange(changeBuilder.range(rangeBuilder.shiftRight().build()).text("p").build());
        server.didChange(changeBuilder.range(rangeBuilder.shiftRight().build()).text("u").build());
        server.didChange(changeBuilder.range(rangeBuilder.shiftRight().build()).text("t").build());
        server.didChange(changeBuilder.range(rangeBuilder.shiftRight().build()).text(":").build());
        server.didChange(changeBuilder.range(rangeBuilder.shiftRight().build()).text(" ").build());
        server.didChange(changeBuilder.range(rangeBuilder.shiftRight().build()).text("G").build());

        server.getState().lifecycleTasks().waitForAllTasks();

        // mostly so you can see what it looks like
        assertThat(server.getState().getManagedDocument(uri).copyText(), equalTo(safeString("""
                $version: "2"

                namespace com.foo

                structure GetFooInput {
                }

                operation GetFoo {
                    input: G
                }
                """)));

        // input: G
        CompletionParams completionParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .position(rangeBuilder.shiftRight().build().getStart())
                .buildCompletion();
        List<CompletionItem> completions = server.completion(completionParams).get().getLeft();

        assertThat(completions, hasItem(hasLabel("GetFooInput")));
    }

    @Test
    public void didChangeReloadsModel() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                operation Foo {}
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        DidOpenTextDocumentParams openParams = new RequestBuilders.DidOpen()
                .uri(uri)
                .text(model)
                .build();
        server.didOpen(openParams);
        assertThat(server.getState().findProjectAndFile(uri).project().modelResult().getValidationEvents(), empty());

        DidChangeTextDocumentParams didChangeParams = new RequestBuilders.DidChange()
                .uri(uri)
                .text("@http(method:\"\", uri: \"\")\n")
                .range(LspAdapter.point(3, 0))
                .build();
        server.didChange(didChangeParams);

        server.getState().lifecycleTasks().getTask(uri).get();

        assertThat(server.getState().findProjectAndFile(uri).project().modelResult().getValidationEvents(),
                containsInAnyOrder(eventWithMessage(containsString("Error creating trait"))));

        DidSaveTextDocumentParams didSaveParams = new RequestBuilders.DidSave().uri(uri).build();
        server.didSave(didSaveParams);

        assertThat(server.getState().findProjectAndFile(uri).project().modelResult().getValidationEvents(),
                containsInAnyOrder(eventWithMessage(containsString("Error creating trait"))));
    }

    @Test
    public void diagnosticsOnMemberTarget() {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                structure Foo {
                    bar: Bar
                }
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);
        String uri = workspace.getUri("main.smithy");

        List<Diagnostic> diagnostics = SmithyDiagnostics.getFileDiagnostics(
                server.getState().findProjectAndFile(uri), server.getMinimumSeverity());

        assertThat(diagnostics, hasSize(1));
        Diagnostic diagnostic = diagnostics.get(0);
        assertThat(diagnostic.getMessage(), startsWith("Target.UnresolvedShape"));

        Document document = server.getState().findProjectAndFile(uri).file().document();
        assertThat(diagnostic.getRange(), hasText(document, equalTo("Bar")));
    }

    @Test
    public void diagnosticsOnInvalidStructureMember() {
        String model = safeString("""
                $version: "2"
                namespace com.foo
                
                structure Foo {
                    abc
                }
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);
        String uri = workspace.getUri("main.smithy");

        List<Diagnostic> diagnostics = SmithyDiagnostics.getFileDiagnostics(
                server.getState().findProjectAndFile(uri), server.getMinimumSeverity());
        assertThat(diagnostics, hasSize(1));

        Diagnostic diagnostic = diagnostics.getFirst();

        assertThat(diagnostic.getRange(), equalTo(
                new Range(
                        new Position(4, 7),
                        new Position(4, 8)
                    )
                )
        );
    }

    @Test
    public void diagnosticsOnUse() {
        String model = safeString("""
                $version: "2"
                namespace com.foo
                
                use mything#SomeUnknownThing
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);
        String uri = workspace.getUri("main.smithy");

        List<Diagnostic> diagnostics = SmithyDiagnostics.getFileDiagnostics(
                server.getState().findProjectAndFile(uri), server.getMinimumSeverity());

        Diagnostic diagnostic = diagnostics.getFirst();
        Document document = server.getState().findProjectAndFile(uri).file().document();

        assertThat(diagnostic.getRange(), hasText(document, equalTo("mything#SomeUnknownThing")));

    }

    @Test
    public void diagnosticOnTrait() {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                structure Foo {
                    @bar
                    bar: String
                }
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);
        String uri = workspace.getUri("main.smithy");

        server.didOpen(new RequestBuilders.DidOpen()
                .uri(uri)
                .text(model)
                .build());

        List<Diagnostic> diagnostics = SmithyDiagnostics.getFileDiagnostics(
                server.getState().findProjectAndFile(uri), server.getMinimumSeverity());

        assertThat(diagnostics, hasSize(1));
        Diagnostic diagnostic = diagnostics.get(0);
        assertThat(diagnostic.getMessage(), startsWith("Model.UnresolvedTrait"));

        Document document = server.getState().findProjectAndFile(uri).file().document();
        assertThat(diagnostic.getRange(), hasText(document, equalTo("@bar")));
    }

    @Test
    public void diagnosticsOnShape() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                list Foo {
                   \s
                }
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        StubClient client = new StubClient();
        SmithyLanguageServer server = new SmithyLanguageServer();
        server.connect(client);

        JsonObject opts = new JsonObject();
        opts.add("diagnostics.minimumSeverity", new JsonPrimitive("NOTE"));
        server.initialize(new RequestBuilders.Initialize()
                .workspaceFolder(workspace.getRoot().toUri().toString(), "test")
                .initializationOptions(opts)
                .build())
                .get();

        String uri = workspace.getUri("main.smithy");

        server.didOpen(new RequestBuilders.DidOpen()
                .uri(uri)
                .text(model)
                .build());

        server.didSave(new RequestBuilders.DidSave()
                .uri(uri)
                .build());

        List<Diagnostic> diagnostics = SmithyDiagnostics.getFileDiagnostics(
                server.getState().findProjectAndFile(uri), server.getMinimumSeverity());

        assertThat(diagnostics, hasSize(1));
        Diagnostic diagnostic = diagnostics.get(0);
        assertThat(diagnostic.getMessage(), containsString("Missing required member"));
        // TODO: In this case, the event is attachedProjects to the shape, but the shape isn't in the model
        //  because it could not be successfully created. So we can't know the actual position of
        //  the shape, because determining it depends on where its defined in the model.
        // assertThat(diagnostic.getRange().getStart(), equalTo(new Position(3, 5)));
        // assertThat(diagnostic.getRange().getEnd(), equalTo(new Position(3, 8)));
    }

    @Test
    public void insideJar() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                structure Foo {
                    bar: PrimitiveInteger
                }
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(model)
                .build());

        Location preludeLocation = server.definition(RequestBuilders.positionRequest()
                .uri(uri)
                .line(4)
                .character(9)
                .buildDefinition())
                .get()
                .getLeft()
                .get(0);

        String preludeUri = preludeLocation.getUri();
        assertThat(preludeUri, startsWith("smithyjar"));

        Hover appliedTraitInPreludeHover = server.hover(RequestBuilders.positionRequest()
                .uri(preludeUri)
                .line(preludeLocation.getRange().getStart().getLine() - 1) // trait applied above 'PrimitiveInteger'
                .character(1)
                .buildHover())
                .get();
        String content = appliedTraitInPreludeHover.getContents().getRight().getValue();
        assertThat(content, containsString("document default"));
    }

    @Test
    public void addingWatchedFile() throws Exception {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String filename = "model/main.smithy";
        String modelText = "";
        workspace.addModel(filename, modelText);
        String uri = workspace.getUri(filename);

        // The file may be opened before the client notifies the server it's been created
        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());

        server.documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(uri)));
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(uri, FileChangeType.Created)
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .range(LspAdapter.origin())
                .text("$")
                .build());

        // Make sure the task is running, then wait for it
        CompletableFuture<Void> future = server.getState().lifecycleTasks().getTask(uri);
        assertThat(future, notNullValue());
        future.get();

        assertManagedMatches(server, uri, Project.Type.NORMAL, workspace.getRoot());
        assertThat(server.getState().findManaged(uri).file().document().copyText(), equalTo("$"));
    }

    @Test
    public void removingWatchedFile() {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        String filename = "model/main.smithy";
        String modelText = safeString("""
                $version: "2"
                namespace com.foo
                string Foo
                """);
        workspace.addModel(filename, modelText);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri(filename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());
        server.didClose(RequestBuilders.didClose()
                .uri(uri)
                .build());
        workspace.deleteModel(filename);
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(uri, FileChangeType.Deleted)
                .build());

        assertThat(server.getState().findManaged(uri), nullValue());
    }

    @Test
    public void addingDetachedFile() {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        String filename = "main.smithy";
        String modelText = safeString("""
                $version: "2"
                namespace com.foo
                string Foo
                """);
        workspace.addModel(filename, modelText);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri(filename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());

        assertManagedMatches(server, uri, Project.Type.DETACHED, uri);

        String movedFilename = "model/main.smithy";
        workspace.moveModel(filename, movedFilename);
        String movedUri = workspace.getUri(movedFilename);
        server.didClose(RequestBuilders.didClose()
                .uri(uri)
                .build());
        server.didOpen(RequestBuilders.didOpen()
                .uri(movedUri)
                .text(modelText)
                .build());
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(movedUri, FileChangeType.Created)
                .build());

        assertThat(server.getState().findManaged(uri), nullValue());
        assertManagedMatches(server, movedUri, Project.Type.NORMAL, workspace.getRoot());
    }

    @Test
    public void removingAttachedFile() {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        String filename = "model/main.smithy";
        String modelText = safeString("""
                $version: "2"
                namespace com.foo
                string Foo
                """);
        workspace.addModel(filename, modelText);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri(filename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());

        assertManagedMatches(server, uri, Project.Type.NORMAL, workspace.getRoot());

        String movedFilename = "main.smithy";
        workspace.moveModel(filename, movedFilename);
        String movedUri = workspace.getUri(movedFilename);

        server.didClose(RequestBuilders.didClose()
                .uri(uri)
                .build());
        server.didOpen(RequestBuilders.didOpen()
                .uri(movedUri)
                .text(modelText)
                .build());
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(uri, FileChangeType.Deleted)
                .build());

        assertThat(server.getState().findManaged(uri), nullValue());
        assertManagedMatches(server, movedUri, Project.Type.DETACHED, movedUri);
    }

    @Test
    public void loadsProjectWithUnNormalizedSourcesDirs() {
        SmithyBuildConfig config = SmithyBuildConfig.builder()
                .version("1")
                .sources(Collections.singletonList("./././smithy"))
                .build();
        String filename = "smithy/main.smithy";
        String modelText = safeString("""
                $version: "2"
                namespace com.foo

                string Foo
                """);
        TestWorkspace workspace = TestWorkspace.builder()
                .withSourceDir(TestWorkspace.dir()
                        .withPath("./smithy")
                        .withSourceFile("main.smithy", modelText))
                .withConfig(config)
                .build();
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri(filename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());

        assertManagedMatches(server, uri, Project.Type.NORMAL, workspace.getRoot());
    }

    @Test
    public void reloadingProjectWithArrayMetadataValues() throws Exception {
        String modelText1 = safeString("""
                $version: "2"

                metadata foo = [1]
                metadata foo = [2]
                metadata bar = {a: [1]}

                namespace com.foo

                string Foo
                """);
        String modelText2 = safeString("""
                $version: "2"

                metadata foo = [3]

                namespace com.foo

                string Bar
                """);
        TestWorkspace workspace = TestWorkspace.multipleModels(modelText1, modelText2);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        Map<String, Node> metadataBefore = server.getState().findProjectByRoot(workspace.getRoot().toString()).modelResult().unwrap().getMetadata();
        assertThat(metadataBefore, hasKey("foo"));
        assertThat(metadataBefore, hasKey("bar"));
        assertThat(metadataBefore.get("foo"), instanceOf(ArrayNode.class));
        assertThat(metadataBefore.get("foo").expectArrayNode().size(), equalTo(3));

        String uri = workspace.getUri("model-0.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText1)
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .range(LspAdapter.lineSpan(8, 0, 0))
                .text(safeString("\nstring Baz\n"))
                .build());
        server.didSave(RequestBuilders.didSave()
                .uri(uri)
                .build());

        server.getState().lifecycleTasks().getTask(uri).get();

        Map<String, Node> metadataAfter = server.getState().findProjectByRoot(workspace.getRoot().toString()).modelResult().unwrap().getMetadata();
        assertThat(metadataAfter, hasKey("foo"));
        assertThat(metadataAfter, hasKey("bar"));
        assertThat(metadataAfter.get("foo"), instanceOf(ArrayNode.class));
        assertThat(metadataAfter.get("foo").expectArrayNode().size(), equalTo(3));

        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .range(LspAdapter.of(2, 0, 3, 0)) // removing the first 'foo' metadata
                .text("")
                .build());

        server.getState().lifecycleTasks().getTask(uri).get();

        Map<String, Node> metadataAfter2 = server.getState().findProjectByRoot(workspace.getRoot().toString()).modelResult().unwrap().getMetadata();
        assertThat(metadataAfter2, hasKey("foo"));
        assertThat(metadataAfter2, hasKey("bar"));
        assertThat(metadataAfter2.get("foo"), instanceOf(ArrayNode.class));
        assertThat(metadataAfter2.get("foo").expectArrayNode().size(), equalTo(2));
    }

    @Test
    public void changingWatchedFilesWithMetadata() throws Exception {
        String modelText1 = safeString("""
                $version: "2"

                metadata foo = [1]
                metadata foo = [2]
                metadata bar = {a: [1]}

                namespace com.foo

                string Foo
                """);
        String modelText2 = safeString("""
                $version: "2"

                metadata foo = [3]

                namespace com.foo

                string Bar
                """);
        TestWorkspace workspace = TestWorkspace.multipleModels(modelText1, modelText2);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        Map<String, Node> metadataBefore = server.getState().findProjectByRoot(workspace.getRoot().toString()).modelResult().unwrap().getMetadata();
        assertThat(metadataBefore, hasKey("foo"));
        assertThat(metadataBefore, hasKey("bar"));
        assertThat(metadataBefore.get("foo"), instanceOf(ArrayNode.class));
        assertThat(metadataBefore.get("foo").expectArrayNode().size(), equalTo(3));

        String uri = workspace.getUri("model-1.smithy");

        workspace.deleteModel("model-1.smithy");
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(uri, FileChangeType.Deleted)
                .build());

        server.getState().lifecycleTasks().waitForAllTasks();

        Map<String, Node> metadataAfter = server.getState().findProjectByRoot(workspace.getRoot().toString()).modelResult().unwrap().getMetadata();
        assertThat(metadataAfter, hasKey("foo"));
        assertThat(metadataAfter, hasKey("bar"));
        assertThat(metadataAfter.get("foo"), instanceOf(ArrayNode.class));
        assertThat(metadataAfter.get("foo").expectArrayNode().size(), equalTo(2));
    }

    @Test
    public void addingOpenedDetachedFile() throws Exception {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        String filename = "main.smithy";
        String modelText = safeString("""
                $version: "2"

                namespace com.foo

                string Foo
                """);
        workspace.addModel(filename, modelText);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        assertThat(server.getState().findManaged(uri), nullValue());

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());

        assertManagedMatches(server, uri, Project.Type.DETACHED, uri);

        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .range(LspAdapter.point(3, 0))
                .text(safeString("string Bar\n"))
                .build());

        // Add the already-opened file to the project
        List<String> updatedSources = new ArrayList<>(workspace.getConfig().getSources());
        updatedSources.add("main.smithy");
        workspace.updateConfig(workspace.getConfig()
                .toBuilder()
                .sources(updatedSources)
                .build());

        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(workspace.getUri("smithy-build.json"), FileChangeType.Changed)
                .build());

        server.getState().lifecycleTasks().waitForAllTasks();

        assertManagedMatches(server, uri, Project.Type.NORMAL, workspace.getRoot());
        assertThat(server.getState().findManaged(uri).project().modelResult().unwrap(), allOf(
                hasShapeWithId("com.foo#Foo"),
                hasShapeWithId("com.foo#Bar")
        ));
    }

    @Test
    public void detachingOpenedFile() throws Exception {
        String modelText = safeString("""
                $version: "2"
                namespace com.foo
                string Foo
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(modelText);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .range(LspAdapter.point(3, 0))
                .text(safeString("string Bar\n"))
                .build());

        workspace.updateConfig(workspace.getConfig()
                .toBuilder()
                .sources(new ArrayList<>())
                .build());

        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(workspace.getUri("smithy-build.json"), FileChangeType.Changed)
                .build());

        server.getState().lifecycleTasks().waitForAllTasks();

        assertManagedMatches(server, uri, Project.Type.DETACHED, uri);
        assertThat(server.getState().findManaged(uri).project().modelResult(), hasValue(allOf(
                hasShapeWithId("com.foo#Foo"),
                hasShapeWithId("com.foo#Bar")
        )));
    }

    @Test
    public void movingDetachedFile() throws Exception {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        String filename = "main.smithy";
        String modelText = safeString("""
                $version: "2"

                namespace com.foo

                string Foo
                """);
        workspace.addModel(filename, modelText);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri(filename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());

        // Moving to an also detachedProjects file - the server doesn't send DidChangeWatchedFiles
        String movedFilename = "main-2.smithy";
        workspace.moveModel(filename, movedFilename);
        String movedUri = workspace.getUri(movedFilename);

        server.didClose(RequestBuilders.didClose()
                .uri(uri)
                .build());
        server.didOpen(RequestBuilders.didOpen()
                .uri(movedUri)
                .text(modelText)
                .build());

        server.getState().lifecycleTasks().waitForAllTasks();

        assertThat(server.getState().findManaged(uri), nullValue());
        assertManagedMatches(server, movedUri, Project.Type.DETACHED, movedUri);
    }

    @Test
    public void updatesDiagnosticsAfterReload() throws Exception {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();

        String filename1 = "model/main.smithy";
        String modelText1 = safeString("""
                $version: "2"

                namespace com.foo

                // using an unknown trait
                @foo
                string Bar
                """);
        workspace.addModel(filename1, modelText1);

        StubClient client = new StubClient();
        SmithyLanguageServer server = initFromWorkspace(workspace, client);

        String uri1 = workspace.getUri(filename1);

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri1)
                .text(modelText1)
                .build());

        server.getState().lifecycleTasks().waitForAllTasks();

        List<PublishDiagnosticsParams> publishedDiagnostics1 = client.diagnostics;
        assertThat(publishedDiagnostics1, hasSize(1));
        assertThat(publishedDiagnostics1.get(0).getUri(), equalTo(uri1));
        assertThat(publishedDiagnostics1.get(0).getDiagnostics(), containsInAnyOrder(
                diagnosticWithMessage(containsString("Model.UnresolvedTrait"))));

        String filename2 = "model/trait.smithy";
        String modelText2 = safeString("""
                $version: "2"

                namespace com.foo

                // adding the missing trait
                @trait
                structure foo {}
                """);
        workspace.addModel(filename2, modelText2);

        String uri2 = workspace.getUri(filename2);

        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(uri2, FileChangeType.Created)
                .build());

        server.getState().lifecycleTasks().waitForAllTasks();

        List<PublishDiagnosticsParams> publishedDiagnostics2 = client.diagnostics;
        assertThat(publishedDiagnostics2, hasSize(2)); // sent more diagnostics
        assertThat(publishedDiagnostics2.get(1).getUri(), equalTo(uri1)); // sent diagnostics for opened file
        assertThat(publishedDiagnostics2.get(1).getDiagnostics(), empty()); // adding the trait cleared the event
    }

    @Test
    public void invalidSyntaxModelPartiallyLoads() {
        String modelText1 = safeString("""
                $version: "2"
                namespace com.foo
                string Foo
                """);
        String modelText2 = safeString("string Bar\n");
        TestWorkspace workspace = TestWorkspace.multipleModels(modelText1, modelText2);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        Project project = server.getState().findProjectByRoot(workspace.getRoot().toString());
        assertThat(project, notNullValue());
        assertThat(project.modelResult().isBroken(), is(true));
        assertThat(project.modelResult().getResult().isPresent(), is(true));
        assertThat(project.modelResult().getResult().get(), hasShapeWithId("com.foo#Foo"));

        String uri = workspace.getUri("model-1.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText2)
                .build());

        assertManagedMatches(server, uri, Project.Type.NORMAL, workspace.getRoot());
    }

    @Test
    public void invalidSyntaxDetachedProjectBecomesValid() throws Exception {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String filename = "main.smithy";
        String modelText = safeString("string Foo\n");
        workspace.addModel(filename, modelText);

        String uri = workspace.getUri(filename);
        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());

        server.getState().lifecycleTasks().waitForAllTasks();

        assertManagedMatches(server, uri, Project.Type.DETACHED, uri);
        ProjectAndFile projectAndFile = server.getState().findProjectAndFile(uri);
        assertThat(projectAndFile, notNullValue());
        assertThat(projectAndFile.project().modelResult().isBroken(), is(true));
        assertThat(projectAndFile.project().modelResult().getResult().isPresent(), is(true));
        assertThat(projectAndFile.project().getAllSmithyFilePaths(), hasItem(endsWith(filename)));

        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .range(LspAdapter.origin())
                .text(safeString("""
                        $version: "2"
                        namespace com.foo
                        """))
                .build());

        server.getState().lifecycleTasks().waitForAllTasks();

        assertManagedMatches(server, uri, Project.Type.DETACHED, uri);
        ProjectAndFile projectAndFile1 = server.getState().findProjectAndFile(uri);
        assertThat(projectAndFile1, notNullValue());
        assertThat(projectAndFile1.project().modelResult().isBroken(), is(false));
        assertThat(projectAndFile1.project().modelResult().getResult().isPresent(), is(true));
        assertThat(projectAndFile1.project().getAllSmithyFilePaths(), hasItem(endsWith(filename)));
        assertThat(projectAndFile1.project().modelResult().unwrap(), hasShapeWithId("com.foo#Foo"));
    }

    @Test
    public void addingDetachedFileWithInvalidSyntax() throws Exception {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String filename = "main.smithy";
        workspace.addModel(filename, "");

        String uri = workspace.getUri(filename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text("")
                .build());

        server.getState().lifecycleTasks().waitForAllTasks();

        assertManagedMatches(server, uri, Project.Type.DETACHED, uri);

        List<String> updatedSources = new ArrayList<>(workspace.getConfig().getSources());
        updatedSources.add(filename);
        workspace.updateConfig(workspace.getConfig()
                .toBuilder()
                .sources(updatedSources)
                .build());

        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(workspace.getUri("smithy-build.json"), FileChangeType.Changed)
                .build());

        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .text(safeString("$version: \"2\"\n"))
                .range(LspAdapter.origin())
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .text(safeString("namespace com.foo\n"))
                .range(LspAdapter.point(1, 0))
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .text(safeString("string Foo\n"))
                .range(LspAdapter.point(2, 0))
                .build());

        server.getState().lifecycleTasks().waitForAllTasks();

        assertManagedMatches(server, uri, Project.Type.NORMAL, workspace.getRoot());
        assertThat(server.getState().findManaged(uri).project().modelResult(), hasValue(hasShapeWithId("com.foo#Foo")));
    }

    @Test
    public void appliedTraitsAreMaintainedInPartialLoad() throws Exception {
        String modelText1 = safeString("""
                $version: "2"
                namespace com.foo
                string Foo
                """);
        String modelText2 = safeString("""
                $version: "2"
                namespace com.foo
                string Bar
                apply Foo @length(min: 1)
                """);
        TestWorkspace workspace = TestWorkspace.multipleModels(modelText1, modelText2);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri2 = workspace.getUri("model-1.smithy");

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri2)
                .text(modelText2)
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(uri2)
                .range(LspAdapter.of(3, 23, 3, 24))
                .text("2")
                .build());

        server.getState().lifecycleTasks().waitForAllTasks();

        ProjectAndFile projectAndFile = server.getState().findProjectAndFile(uri2);
        assertThat(projectAndFile, notNullValue());
        assertThat(projectAndFile.project().modelResult(), hasValue(hasShapeWithId("com.foo#Foo")));
        assertThat(projectAndFile.project().modelResult(), hasValue(hasShapeWithId("com.foo#Bar")));

        Shape foo = projectAndFile.project().modelResult().getResult().get().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.getIntroducedTraits().keySet(), containsInAnyOrder(LengthTrait.ID));
        assertThat(foo.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(2L)));

        String uri1 = workspace.getUri("model-0.smithy");

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri1)
                .text(modelText1)
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(uri1)
                .range(LspAdapter.point(3, 0))
                .text(safeString("string Another\n"))
                .build());

        server.getState().lifecycleTasks().waitForAllTasks();

        projectAndFile = server.getState().findProjectAndFile(uri1);
        assertThat(projectAndFile, notNullValue());
        assertThat(projectAndFile.project().modelResult(), hasValue(hasShapeWithId("com.foo#Foo")));
        assertThat(projectAndFile.project().modelResult(), hasValue(hasShapeWithId("com.foo#Bar")));
        assertThat(projectAndFile.project().modelResult(), hasValue(hasShapeWithId("com.foo#Another")));

        foo = projectAndFile.project().modelResult().getResult().get().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.getIntroducedTraits().keySet(), containsInAnyOrder(LengthTrait.ID));
        assertThat(foo.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(2L)));
    }

    @Test
    public void brokenBuildFileEventuallyConsistent() throws Exception {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        SmithyLanguageServer server = initFromWorkspace(workspace);

        workspace.addModel("model/main.smithy", "");
        String uri = workspace.getUri("model/main.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text("")
                .build());
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(uri, FileChangeType.Created)
                .build());

        String buildJson = workspace.readFile("smithy-build.json");
        server.didOpen(RequestBuilders.didOpen()
                .uri(workspace.getUri("smithy-build.json"))
                .text(buildJson)
                .build());

        String invalidDependency = "software.amazon.smithy:smithy-smoke-test-traits:[1.0, 2.0[";
        workspace.updateConfig(workspace.getConfig().toBuilder()
                .maven(MavenConfig.builder()
                        .dependencies(Collections.singletonList(invalidDependency))
                        .build())
                .build());
        buildJson = workspace.readFile("smithy-build.json");
        server.didChange(RequestBuilders.didChange()
                .uri(workspace.getUri("smithy-build.json"))
                .text(buildJson)
                .build());
        server.didSave(RequestBuilders.didSave()
                .uri(workspace.getUri("smithy-build.json"))
                .build());

        String fixed = "software.amazon.smithy:smithy-smoke-test-traits:1.49.0";
        workspace.updateConfig(workspace.getConfig().toBuilder()
                .maven(MavenConfig.builder()
                        .dependencies(Collections.singletonList(fixed))
                        .build())
                .build());
        buildJson = workspace.readFile("smithy-build.json");
        server.didChange(RequestBuilders.didChange()
                .uri(workspace.getUri("smithy-build.json"))
                .text(buildJson)
                .build());
        server.didSave(RequestBuilders.didSave()
                .uri(workspace.getUri("smithy-build.json"))
                .build());

        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .text(safeString("""
                        $version: "2"
                        namespace com.foo
                        string Foo
                        """))
                .range(LspAdapter.origin())
                .build());
        server.getState().lifecycleTasks().waitForAllTasks();

        ProjectAndFile projectAndFile = server.getState().findProjectAndFile(uri);
        assertThat(projectAndFile, notNullValue());
        assertThat(projectAndFile.project().modelResult(), hasValue(hasShapeWithId("com.foo#Foo")));
    }

    @Test
    public void loadsMultipleRoots() {
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withPath("foo")
                .withSourceFile("foo.smithy", """
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """)
                .build();

        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withPath("bar")
                .withSourceFile("bar.smithy", """
                        $version: "2"
                        namespace com.bar
                        structure Bar {}
                        """)
                .build();

        SmithyLanguageServer server = initFromWorkspaces(workspaceFoo, workspaceBar);

        Project projectFoo = server.getState().findProjectByRoot(workspaceFoo.getName());
        Project projectBar = server.getState().findProjectByRoot(workspaceBar.getName());

        assertThat(projectFoo, notNullValue());
        assertThat(projectBar, notNullValue());

        assertThat(projectFoo.getAllSmithyFilePaths(), hasItem(endsWith("foo.smithy")));
        assertThat(projectBar.getAllSmithyFilePaths(), hasItem(endsWith("bar.smithy")));

        assertThat(projectFoo.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.foo#Foo")));
        assertThat(projectBar.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.bar#Bar")));
    }

    @Test
    public void multiRootLifecycleManagement() throws Exception {
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withPath("foo")
                .withSourceFile("foo.smithy", """
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """)
                .build();

        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withPath("bar")
                .withSourceFile("bar.smithy", """
                        $version: "2"
                        namespace com.bar
                        structure Bar {}
                        """)
                .build();

        SmithyLanguageServer server = initFromWorkspaces(workspaceFoo, workspaceBar);

        String fooUri = workspaceFoo.getUri("foo.smithy");
        String barUri = workspaceBar.getUri("bar.smithy");

        server.didOpen(RequestBuilders.didOpen()
                .uri(fooUri)
                .build());
        server.didOpen(RequestBuilders.didOpen()
                .uri(barUri)
                .build());

        server.didChange(RequestBuilders.didChange()
                .uri(fooUri)
                .text("\nstructure Bar {}")
                .range(LspAdapter.point(server.getState().findProjectAndFile(fooUri).file().document().end()))
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(barUri)
                .text("\nstructure Foo {}")
                .range(LspAdapter.point(server.getState().findProjectAndFile(barUri).file().document().end()))
                .build());

        server.didSave(RequestBuilders.didSave()
                .uri(fooUri)
                .build());
        server.didSave(RequestBuilders.didSave()
                .uri(barUri)
                .build());

        server.getState().lifecycleTasks().waitForAllTasks();

        Project projectFoo = server.getState().findProjectByRoot(workspaceFoo.getName());
        Project projectBar = server.getState().findProjectByRoot(workspaceBar.getName());

        assertThat(projectFoo.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.foo#Foo")));
        assertThat(projectFoo.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.foo#Bar")));

        assertThat(projectBar.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.bar#Bar")));
        assertThat(projectBar.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.bar#Foo")));
    }

    @Test
    public void multiRootAddingWatchedFile() throws Exception {
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withPath("foo")
                .withSourceDir(new TestWorkspace.Dir()
                        .withPath("model")
                        .withSourceFile("main.smithy", ""))
                .build();
        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withPath("bar")
                .withSourceDir(new TestWorkspace.Dir()
                        .withPath("model")
                        .withSourceFile("main.smithy", ""))
                .build();

        SmithyLanguageServer server = initFromWorkspaces(workspaceFoo, workspaceBar);

        String fooUri = workspaceFoo.getUri("model/main.smithy");
        String barUri = workspaceBar.getUri("model/main.smithy");

        String newFilename = "model/other.smithy";
        String newText = """
                $version: "2"
                namespace com.bar
                structure Bar {}
                """;
        workspaceBar.addModel(newFilename, newText);

        String newUri = workspaceBar.getUri(newFilename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(fooUri)
                .build());
        server.didOpen(RequestBuilders.didOpen()
                .uri(barUri)
                .build());

        server.didChange(RequestBuilders.didChange()
                .uri(fooUri)
                .text("""
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """)
                .range(LspAdapter.origin())
                .build());

        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(newUri, FileChangeType.Created)
                .build());

        server.didChange(RequestBuilders.didChange()
                .uri(fooUri)
                .text("""
                        
                        structure Bar {}""")
                .range(LspAdapter.point(3, 0))
                .build());

        server.getState().lifecycleTasks().waitForAllTasks();

        Project projectFoo = server.getState().findProjectByRoot(workspaceFoo.getName());
        Project projectBar = server.getState().findProjectByRoot(workspaceBar.getName());

        assertThat(projectFoo.getAllSmithyFilePaths(), hasItem(endsWith("main.smithy")));
        assertThat(projectBar.getAllSmithyFilePaths(), hasItem(endsWith("main.smithy")));
        assertThat(projectBar.getAllSmithyFilePaths(), hasItem(endsWith("other.smithy")));

        assertThat(projectFoo.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.foo#Foo")));
        assertThat(projectFoo.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.foo#Bar")));
        assertThat(projectBar.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.bar#Bar")));
    }

    @Test
    public void multiRootChangingBuildFile() throws Exception {
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withPath("foo")
                .withSourceDir(new TestWorkspace.Dir()
                        .withPath("model")
                        .withSourceFile("main.smithy", ""))
                .build();
        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withPath("bar")
                .withSourceDir(new TestWorkspace.Dir()
                        .withPath("model")
                        .withSourceFile("main.smithy", ""))
                .build();

        SmithyLanguageServer server = initFromWorkspaces(workspaceFoo, workspaceBar);

        String newFilename = "other.smithy";
        String newText = """
                $version: "2"
                namespace com.other
                structure Other {}
                """;
        workspaceBar.addModel(newFilename, newText);
        String newUri = workspaceBar.getUri(newFilename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(newUri)
                .text(newText)
                .build());

        List<String> updatedSources = new ArrayList<>(workspaceBar.getConfig().getSources());
        updatedSources.add(newFilename);
        workspaceBar.updateConfig(workspaceBar.getConfig().toBuilder()
                .sources(updatedSources)
                .build());

        server.didOpen(RequestBuilders.didOpen()
                .uri(workspaceFoo.getUri("model/main.smithy"))
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(workspaceFoo.getUri("model/main.smithy"))
                .text("""
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """)
                .range(LspAdapter.origin())
                .build());

        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(workspaceBar.getUri("smithy-build.json"), FileChangeType.Changed)
                .build());

        server.didOpen(RequestBuilders.didOpen()
                .uri(workspaceBar.getUri("model/main.smithy"))
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(workspaceBar.getUri("model/main.smithy"))
                .text("""
                        $version: "2"
                        namespace com.bar
                        structure Bar {
                            other: com.other#Other
                        }
                        """)
                .range(LspAdapter.origin())
                .build());

        server.getState().lifecycleTasks().waitForAllTasks();

        assertThat(server.getState().findProjectAndFile(newUri), notNullValue());
        assertThat(server.getState().findProjectAndFile(workspaceBar.getUri("model/main.smithy")), notNullValue());
        assertThat(server.getState().findProjectAndFile(workspaceFoo.getUri("model/main.smithy")), notNullValue());

        Project projectFoo = server.getState().findProjectByRoot(workspaceFoo.getName());
        Project projectBar = server.getState().findProjectByRoot(workspaceBar.getName());

        assertThat(projectFoo.getAllSmithyFilePaths(), hasItem(endsWith("main.smithy")));
        assertThat(projectBar.getAllSmithyFilePaths(), hasItem(endsWith("main.smithy")));
        assertThat(projectBar.getAllSmithyFilePaths(), hasItem(endsWith("other.smithy")));

        assertThat(projectFoo.modelResult(), hasValue(hasShapeWithId("com.foo#Foo")));
        assertThat(projectBar.modelResult(), hasValue(hasShapeWithId("com.bar#Bar")));
        assertThat(projectBar.modelResult(), hasValue(hasShapeWithId("com.bar#Bar$other")));
        assertThat(projectBar.modelResult(), hasValue(hasShapeWithId("com.other#Other")));
    }

    @Test
    public void addingWorkspaceFolder() throws Exception {
        String fooModel = """
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """;
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withPath("foo")
                .withSourceFile("foo.smithy", fooModel)
                .build();

        SmithyLanguageServer server = initFromWorkspace(workspaceFoo);

        String barModel = """
                        $version: "2"
                        namespace com.bar
                        structure Bar {}
                        """;
        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withPath("bar")
                .withSourceFile("bar.smithy", barModel)
                .build();

        server.didOpen(RequestBuilders.didOpen()
                .uri(workspaceFoo.getUri("foo.smithy"))
                .text(fooModel)
                .build());

        server.didChangeWorkspaceFolders(RequestBuilders.didChangeWorkspaceFolders()
                .added(workspaceBar.getRoot().toUri().toString(), "bar")
                .build());

        server.didOpen(RequestBuilders.didOpen()
                .uri(workspaceBar.getUri("bar.smithy"))
                .text(barModel)
                .build());

        server.getState().lifecycleTasks().waitForAllTasks();

        assertManagedMatches(server, workspaceFoo.getUri("foo.smithy"), Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, workspaceBar.getUri("bar.smithy"), Project.Type.NORMAL, workspaceBar.getRoot());

        Project projectFoo = server.getState().findProjectByRoot(workspaceFoo.getName());
        Project projectBar = server.getState().findProjectByRoot(workspaceBar.getName());

        assertThat(projectFoo.getAllSmithyFilePaths(), hasItem(endsWith("foo.smithy")));
        assertThat(projectBar.getAllSmithyFilePaths(), hasItem(endsWith("bar.smithy")));

        assertThat(projectFoo.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.foo#Foo")));
        assertThat(projectBar.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.bar#Bar")));
    }

    @Test
    public void removingWorkspaceFolder() {
        String fooModel = """
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """;
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withPath("foo")
                .withSourceFile("foo.smithy", fooModel)
                .build();

        String barModel = """
                        $version: "2"
                        namespace com.bar
                        structure Bar {}
                        """;
        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withPath("bar")
                .withSourceFile("bar.smithy", barModel)
                .build();

        SmithyLanguageServer server = initFromWorkspaces(workspaceFoo, workspaceBar);

        String fooUri = workspaceFoo.getUri("foo.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(fooUri)
                .text(fooModel)
                .build());

        String barUri = workspaceBar.getUri("bar.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(barUri)
                .text(barModel)
                .build());

        server.didChangeWorkspaceFolders(RequestBuilders.didChangeWorkspaceFolders()
                .removed(workspaceBar.getRoot().toUri().toString(), "bar")
                .build());

        assertManagedMatches(server, fooUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, barUri, Project.Type.DETACHED, barUri);

        Project projectFoo = server.getState().findProjectByRoot(workspaceFoo.getName());
        Project projectBar = server.getState().findProjectByRoot(barUri);

        assertThat(projectFoo.getAllSmithyFilePaths(), hasItem(endsWith("foo.smithy")));
        assertThat(projectBar.getAllSmithyFilePaths(), hasItem(endsWith("bar.smithy")));

        assertThat(projectFoo.modelResult(), hasValue(hasShapeWithId("com.foo#Foo")));
        assertThat(projectBar.modelResult(), hasValue(hasShapeWithId("com.bar#Bar")));
    }

    @Test
    public void singleWorkspaceMultiRoot() throws Exception {
        Path root = Files.createTempDirectory("test");
        root.toFile().deleteOnExit();

        String fooModel = """
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """;
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withRoot(root)
                .withPath("foo")
                .withSourceFile("foo.smithy", fooModel)
                .build();

        String barModel = """
                        $version: "2"
                        namespace com.bar
                        structure Bar {}
                        """;
        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withRoot(root)
                .withPath("bar")
                .withSourceFile("bar.smithy", barModel)
                .build();

        SmithyLanguageServer server = initFromRoot(root);

        assertThat(server.getState().findProjectByRoot(workspaceFoo.getName()), notNullValue());
        assertThat(server.getState().findProjectByRoot(workspaceBar.getName()), notNullValue());
        assertThat(server.getState().workspacePaths(), contains(root));
    }

    @Test
    public void addingRootsToWorkspace() throws Exception {
        Path root = Files.createTempDirectory("test");
        root.toFile().deleteOnExit();

        SmithyLanguageServer server = initFromRoot(root);

        String fooModel = """
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """;
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withRoot(root)
                .withPath("foo")
                .withSourceFile("foo.smithy", fooModel)
                .build();

        String barModel = """
                        $version: "2"
                        namespace com.bar
                        structure Bar {}
                        """;
        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withRoot(root)
                .withPath("bar")
                .withSourceFile("bar.smithy", barModel)
                .build();

        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(workspaceFoo.getUri("smithy-build.json"), FileChangeType.Created)
                .event(workspaceBar.getUri("smithy-build.json"), FileChangeType.Created)
                .build());

        assertThat(server.getState().workspacePaths(), contains(root));
        assertThat(server.getState().findProjectByRoot(workspaceFoo.getName()), notNullValue());
        assertThat(server.getState().findProjectByRoot(workspaceBar.getName()), notNullValue());
    }

    @Test
    public void removingRootsFromWorkspace() throws Exception {
        Path root = Files.createTempDirectory("test");
        root.toFile().deleteOnExit();

        String fooModel = """
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """;
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withRoot(root)
                .withPath("foo")
                .withSourceFile("foo.smithy", fooModel)
                .build();

        String barModel = """
                        $version: "2"
                        namespace com.bar
                        structure Bar {}
                        """;
        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withRoot(root)
                .withPath("bar")
                .withSourceFile("bar.smithy", barModel)
                .build();

        SmithyLanguageServer server = initFromRoot(root);

        assertThat(server.getState().workspacePaths(), contains(root));
        assertThat(server.getState().findProjectByRoot(workspaceFoo.getName()), notNullValue());
        assertThat(server.getState().findProjectByRoot(workspaceBar.getName()), notNullValue());

        workspaceFoo.deleteModel("smithy-build.json");

        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(workspaceFoo.getUri("smithy-build.json"), FileChangeType.Deleted)
                .build());

        assertThat(server.getState().workspacePaths(), contains(root));
        assertThat(server.getState().findProjectByRoot(workspaceFoo.getName()), nullValue());
        assertThat(server.getState().findProjectByRoot(workspaceBar.getName()), notNullValue());
    }

    @Test
    public void addingConfigFile() throws Exception {
        Path root = Files.createTempDirectory("test");
        root.toFile().deleteOnExit();

        String fooModel = """
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """;
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withRoot(root)
                .withPath("foo")
                .withSourceFile("foo.smithy", fooModel)
                .build();

        String barModel = """
                        $version: "2"
                        namespace com.bar
                        structure Bar {}
                        """;
        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withRoot(root)
                .withPath("bar")
                .withSourceFile("bar.smithy", barModel)
                .build();

        SmithyLanguageServer server = initFromRoot(root);

        String fooUri = workspaceFoo.getUri("foo.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(fooUri)
                .text(fooModel)
                .build());

        String barUri = workspaceBar.getUri("bar.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(barUri)
                .text(barModel)
                .build());

        String bazModel = """
                        $version: "2"
                        namespace com.baz
                        structure Baz {}
                        """;
        workspaceFoo.addModel("baz.smithy", bazModel);
        String bazUri = workspaceFoo.getUri("baz.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(bazUri)
                .text(bazModel)
                .build());

        assertThat(server.getState().workspacePaths(), contains(root));
        assertManagedMatches(server, fooUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, barUri, Project.Type.NORMAL, workspaceBar.getRoot());
        assertManagedMatches(server, bazUri, Project.Type.DETACHED, bazUri);

        workspaceFoo.addModel(".smithy-project.json", """
                {
                    "sources": ["baz.smithy"]
                }""");
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(workspaceFoo.getUri(".smithy-project.json"), FileChangeType.Created)
                .build());

        assertManagedMatches(server, fooUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, barUri, Project.Type.NORMAL, workspaceBar.getRoot());
        assertManagedMatches(server, bazUri, Project.Type.NORMAL, workspaceFoo.getRoot());
    }

    @Test
    public void removingConfigFile() throws Exception {
        Path root = Files.createTempDirectory("test");
        root.toFile().deleteOnExit();

        String fooModel = """
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """;
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withRoot(root)
                .withPath("foo")
                .withSourceFile("foo.smithy", fooModel)
                .build();
        String bazModel = """
                        $version: "2"
                        namespace com.baz
                        structure Baz {}
                        """;
        workspaceFoo.addModel("baz.smithy", bazModel);
        workspaceFoo.addModel(".smithy-project.json", """
                {
                    "sources": ["baz.smithy"]
                }""");

        String barModel = """
                        $version: "2"
                        namespace com.bar
                        structure Bar {}
                        """;
        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withRoot(root)
                .withPath("bar")
                .withSourceFile("bar.smithy", barModel)
                .build();

        SmithyLanguageServer server = initFromRoot(root);

        String fooUri = workspaceFoo.getUri("foo.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(fooUri)
                .text(fooModel)
                .build());

        String barUri = workspaceBar.getUri("bar.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(barUri)
                .text(barModel)
                .build());

        String bazUri = workspaceFoo.getUri("baz.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(bazUri)
                .text(bazModel)
                .build());

        assertThat(server.getState().workspacePaths(), contains(root));
        assertManagedMatches(server, fooUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, barUri, Project.Type.NORMAL, workspaceBar.getRoot());
        assertManagedMatches(server, bazUri, Project.Type.NORMAL, workspaceFoo.getRoot());

        workspaceFoo.deleteModel(".smithy-project.json");
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(workspaceFoo.getUri(".smithy-project.json"), FileChangeType.Deleted)
                .build());

        assertManagedMatches(server, fooUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, barUri, Project.Type.NORMAL, workspaceBar.getRoot());
        assertManagedMatches(server, bazUri, Project.Type.DETACHED, bazUri);
    }

    @Test
    public void tracksJsonFiles() {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        workspace.addModel("model/main.json","""
                {
                    "smithy": "2.0",
                    "shapes": {
                        "com.foo#Foo": {
                            "type": "structure"
                        }
                    }
                }
                """);
        SmithyLanguageServer server = initFromWorkspaces(workspace);

        Project project = server.getState().findProjectByRoot(workspace.getName());
        assertThat(project.modelResult(), hasValue(hasShapeWithId("com.foo#Foo")));
    }

    @Test
    public void tracksBuildFileChanges() {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        SmithyLanguageServer server = initFromWorkspaces(workspace);

        String smithyBuildJson = workspace.readFile("smithy-build.json");
        String uri = workspace.getUri("smithy-build.json");

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(smithyBuildJson)
                .build());

        assertManagedMatches(server, uri, Project.Type.NORMAL, workspace.getRoot());
        assertThat(server.getState().getManagedDocument(uri).copyText(), equalTo(smithyBuildJson));

        String updatedSmithyBuildJson = """
                {
                    "version": "1.0",
                    "sources": ["foo.smithy"]
                }
                """;
        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .text(updatedSmithyBuildJson)
                .build());
        assertThat(server.getState().getManagedDocument(uri).copyText(), equalTo(updatedSmithyBuildJson));

        server.didSave(RequestBuilders.didSave()
                .uri(uri)
                .build());
        server.didClose(RequestBuilders.didClose()
                .uri(uri)
                .build());

        assertThat(server.getState().findManaged(uri), nullValue());
    }

    @Test
    public void reloadsProjectOnBuildFileSave() {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        SmithyLanguageServer server = initFromWorkspaces(workspace);

        String buildJson = workspace.readFile("smithy-build.json");
        String buildJsonUri = workspace.getUri("smithy-build.json");

        server.didOpen(RequestBuilders.didOpen()
                .uri(buildJsonUri)
                .text(buildJson)
                .build());

        String model = """
                namespace com.foo
                string Foo
                """;
        workspace.addModel("foo.smithy", model);
        String fooUri = workspace.getUri("foo.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(fooUri)
                .text(model)
                .build());

        assertManagedMatches(server, fooUri, Project.Type.DETACHED, fooUri);

        String updatedBuildJson = """
                {
                    "version": "1.0",
                    "sources": ["foo.smithy"]
                }
                """;
        server.didChange(RequestBuilders.didChange()
                .uri(buildJsonUri)
                .text(updatedBuildJson)
                .build());
        server.didSave(RequestBuilders.didSave()
                .uri(buildJsonUri)
                .build());

        assertManagedMatches(server, fooUri, Project.Type.NORMAL, workspace.getRoot());
    }

    @Test
    public void testCustomServerOptions() {
        ServerOptions options = ServerOptions.builder()
                .setMinimumSeverity(Severity.NOTE)
                .setOnlyReloadOnSave(true)
                .build();

        assertThat(options.getMinimumSeverity(), equalTo(Severity.NOTE));
        assertThat(options.getOnlyReloadOnSave(), equalTo(true));
    }

    @Test
    public void testFromInitializeParamsWithValidOptions() {
        StubClient client = new StubClient();
        // Create initialization options
        JsonObject opts = new JsonObject();
        opts.add("diagnostics.minimumSeverity", new JsonPrimitive("ERROR"));
        opts.add("onlyReloadOnSave", new JsonPrimitive(true));

        // Create InitializeParams with the options
        InitializeParams params = new InitializeParams();
        params.setInitializationOptions(opts);

        // Call the method being tested
        ServerOptions options = ServerOptions.fromInitializeParams(params, new SmithyLanguageClient(client));

        assertThat(options.getMinimumSeverity(), equalTo(Severity.ERROR));
        assertThat(options.getOnlyReloadOnSave(), equalTo(true));
    }

    @Test
    public void testFromInitializeParamsWithPartialOptions() {
        StubClient client = new StubClient();
        JsonObject opts = new JsonObject();
        opts.add("onlyReloadOnSave", new JsonPrimitive(true));
        // Not setting minimumSeverity

        // Create InitializeParams with the options
        InitializeParams params = new InitializeParams();
        params.setInitializationOptions(opts);

        ServerOptions options = ServerOptions.fromInitializeParams(params, new SmithyLanguageClient(client));

        assertThat(options.getMinimumSeverity(), equalTo(Severity.WARNING)); // Default value
        assertThat(options.getOnlyReloadOnSave(), equalTo(true)); // Explicitly set value
    }

    @Test
    public void openingNewBuildFileInExistingProjectBeforeDidChangeWatchedFiles() {
        TestWorkspace workspace = TestWorkspace.emptyWithNoConfig("test");

        String fooModel = """
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """;
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withRoot(workspace.getRoot())
                .withPath("foo")
                .withSourceFile("foo.smithy", fooModel)
                .build();

        SmithyLanguageServer server = initFromRoot(workspace.getRoot());

        String fooUri = workspaceFoo.getUri("foo.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(fooUri)
                .text(fooModel)
                .build());

        String bazModel = """
                        $version: "2"
                        namespace com.baz
                        structure Baz {}
                        """;
        workspaceFoo.addModel("baz.smithy", bazModel);
        String bazUri = workspaceFoo.getUri("baz.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(bazUri)
                .text(bazModel)
                .build());

        assertThat(server.getState().workspacePaths(), contains(workspace.getRoot()));
        assertManagedMatches(server, fooUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, bazUri, Project.Type.DETACHED, bazUri);

        String smithyProjectJson = """
                {
                    "sources": ["baz.smithy"]
                }""";
        workspaceFoo.addModel(".smithy-project.json", smithyProjectJson);
        String smithyProjectJsonUri = workspaceFoo.getUri(".smithy-project.json");
        server.didOpen(RequestBuilders.didOpen()
                .uri(smithyProjectJsonUri)
                .text(smithyProjectJson)
                .build());

        assertManagedMatches(server, fooUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, bazUri, Project.Type.DETACHED, bazUri);
        assertManagedMatches(server, smithyProjectJsonUri, Project.Type.UNRESOLVED, smithyProjectJsonUri);

        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(smithyProjectJsonUri, FileChangeType.Created)
                .build());

        assertManagedMatches(server, fooUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, bazUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, smithyProjectJsonUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertThat(server.getState().getAllProjects().size(), is(1));
    }

    @Test
    public void openingNewBuildFileInNewProjectBeforeDidChangeWatchedFiles() {
        TestWorkspace workspace = TestWorkspace.emptyWithNoConfig("test");
        String fooModel = """
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """;
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withRoot(workspace.getRoot())
                .withPath("foo")
                .withSourceFile("foo.smithy", fooModel)
                .build();

        SmithyLanguageServer server = initFromRoot(workspace.getRoot());

        String fooUri = workspaceFoo.getUri("foo.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(fooUri)
                .text(fooModel)
                .build());

        String barModel = """
                $version: "2"
                namespace com.bar
                structure Bar {}
                """;
        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withRoot(workspace.getRoot())
                .withPath("bar")
                .withSourceFile("bar.smithy", barModel)
                .build();
        String barUri = workspaceBar.getUri("bar.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(barUri)
                .text(barModel)
                .build());

        assertThat(server.getState().workspacePaths(), contains(workspace.getRoot()));
        assertManagedMatches(server, fooUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, barUri, Project.Type.DETACHED, barUri);

        String barSmithyBuildJson = """
                {
                    "version": "1",
                    "sources": ["bar.smithy"]
                }""";
        workspaceBar.addModel("smithy-build.json", barSmithyBuildJson);
        String barSmithyBuildUri = workspaceBar.getUri("smithy-build.json");
        server.didOpen(RequestBuilders.didOpen()
                .uri(barSmithyBuildUri)
                .text(barSmithyBuildJson)
                .build());

        assertManagedMatches(server, fooUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, barUri, Project.Type.DETACHED, barUri);
        assertManagedMatches(server, barSmithyBuildUri, Project.Type.UNRESOLVED, barSmithyBuildUri);

        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(barSmithyBuildUri, FileChangeType.Created)
                .build());

        assertManagedMatches(server, fooUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, barUri, Project.Type.NORMAL, workspaceBar.getRoot());
        assertManagedMatches(server, barSmithyBuildUri, Project.Type.NORMAL, workspaceBar.getRoot());
    }

    @Test
    public void openingConfigFileInEmptyWorkspaceBeforeDidChangeWatchedFiles() {
        String fooModel = """
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """;
        TestWorkspace workspaceFoo = TestWorkspace.singleModel(fooModel);

        SmithyLanguageServer server = initFromWorkspace(workspaceFoo);

        TestWorkspace workspaceBar = TestWorkspace.emptyWithNoConfig("bar");
        server.didChangeWorkspaceFolders(RequestBuilders.didChangeWorkspaceFolders()
                .added(workspaceBar.getRoot().toUri().toString(), "bar")
                .build());

        assertThat(server.getState().workspacePaths(), containsInAnyOrder(
                workspaceFoo.getRoot(),
                workspaceBar.getRoot()));

        String barModel = """
                        $version: "2"
                        namespace com.bar
                        structure Bar {}
                        """;
        workspaceBar.addModel("bar.smithy", barModel);
        String barUri = workspaceBar.getUri("bar.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(barUri)
                .text(barModel)
                .build());

        String fooUri = workspaceFoo.getUri("main.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(fooUri)
                .text(fooModel)
                .build());

        assertManagedMatches(server, fooUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, barUri, Project.Type.DETACHED, barUri);

        String barSmithyBuildJson = """
                {
                    "version": "1",
                    "sources": ["bar.smithy"]
                }""";
        workspaceBar.addModel("smithy-build.json", barSmithyBuildJson);
        String barSmithyBuildUri = workspaceBar.getUri("smithy-build.json");
        server.didOpen(RequestBuilders.didOpen()
                .uri(barSmithyBuildUri)
                .text(barSmithyBuildJson)
                .build());

        assertManagedMatches(server, fooUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, barUri, Project.Type.DETACHED, barUri);
        assertManagedMatches(server, barSmithyBuildUri, Project.Type.UNRESOLVED, barSmithyBuildUri);

        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(barSmithyBuildUri, FileChangeType.Created)
                .build());

        assertManagedMatches(server, fooUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, barUri, Project.Type.NORMAL, workspaceBar.getRoot());
        assertManagedMatches(server, barSmithyBuildUri, Project.Type.NORMAL, workspaceBar.getRoot());
    }

    @Test
    public void openingConfigFileInEmptyWorkspaceAfterDidChangeWatchedFiles() {
        String fooModel = """
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """;
        TestWorkspace workspaceFoo = TestWorkspace.singleModel(fooModel);

        SmithyLanguageServer server = initFromWorkspace(workspaceFoo);

        TestWorkspace workspaceBar = TestWorkspace.emptyWithNoConfig("bar");
        server.didChangeWorkspaceFolders(RequestBuilders.didChangeWorkspaceFolders()
                .added(workspaceBar.getRoot().toUri().toString(), "bar")
                .build());

        assertThat(server.getState().workspacePaths(), containsInAnyOrder(
                workspaceFoo.getRoot(),
                workspaceBar.getRoot()));

        String barModel = """
                        $version: "2"
                        namespace com.bar
                        structure Bar {}
                        """;
        workspaceBar.addModel("bar.smithy", barModel);
        String barUri = workspaceBar.getUri("bar.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(barUri)
                .text(barModel)
                .build());

        String fooUri = workspaceFoo.getUri("main.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(fooUri)
                .text(fooModel)
                .build());

        assertManagedMatches(server, fooUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, barUri, Project.Type.DETACHED, barUri);

        String barSmithyBuildJson = """
                {
                    "version": "1",
                    "sources": ["bar.smithy"]
                }""";
        workspaceBar.addModel("smithy-build.json", barSmithyBuildJson);
        String barSmithyBuildUri = workspaceBar.getUri("smithy-build.json");
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(barSmithyBuildUri, FileChangeType.Created)
                .build());

        server.didOpen(RequestBuilders.didOpen()
                .uri(barSmithyBuildUri)
                .text(barSmithyBuildJson)
                .build());

        assertManagedMatches(server, fooUri, Project.Type.NORMAL, workspaceFoo.getRoot());
        assertManagedMatches(server, barUri, Project.Type.NORMAL, workspaceBar.getRoot());
        assertManagedMatches(server, barSmithyBuildUri, Project.Type.NORMAL, workspaceBar.getRoot());
    }

    @Test
    public void foo() {
        TestWorkspace workspace = TestWorkspace.emptyWithNoConfig("foo");
        SmithyLanguageServer server = initFromRoot(workspace.getRoot());

        String fooModel = """
                $version: "2"
                namespace com.foo
                structure Foo {}
                """;
        workspace.addModel("foo.smithy", fooModel);
        String fooUri = workspace.getUri("foo.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(fooUri)
                .text(fooModel)
                .build());

        assertManagedMatches(server, fooUri, Project.Type.DETACHED, fooUri);
    }

    private void assertManagedMatches(
            SmithyLanguageServer server,
            String uri,
            Project.Type expectedType,
            String expectedRootUri
    ) {
        ProjectAndFile projectAndFile = server.getState().findManaged(uri);
        assertThat(projectAndFile, notNullValue());
        assertThat(projectAndFile.project().type(), equalTo(expectedType));
        assertThat(projectAndFile.project().root().toString(), equalTo(LspAdapter.toPath(expectedRootUri)));
    }

    private void assertManagedMatches(
            SmithyLanguageServer server,
            String uri,
            Project.Type expectedType,
            Path expectedRootPath
    ) {
        ProjectAndFile projectAndFile = server.getState().findManaged(uri);
        assertThat(projectAndFile, notNullValue());
        assertThat(projectAndFile.project().type(), equalTo(expectedType));
        assertThat(projectAndFile.project().root(), equalTo(expectedRootPath));
    }

    public static SmithyLanguageServer initFromWorkspace(TestWorkspace workspace) {
        return initFromWorkspace(workspace, new StubClient());
    }

    public static SmithyLanguageServer initFromWorkspace(TestWorkspace workspace, LanguageClient client) {
        try {
            SmithyLanguageServer server = new SmithyLanguageServer();
            server.connect(client);

            server.initialize(RequestBuilders.initialize()
                    .workspaceFolder(workspace.getRoot().toUri().toString(), workspace.getName())
                    .build())
                    .get();

            return server;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static SmithyLanguageServer initFromWorkspaces(TestWorkspace... workspaces) {
        LanguageClient client = new StubClient();
        SmithyLanguageServer server = new SmithyLanguageServer();
        server.connect(client);

        RequestBuilders.Initialize initialize = RequestBuilders.initialize();
        for (TestWorkspace workspace : workspaces) {
            initialize.workspaceFolder(workspace.getRoot().toUri().toString(), workspace.getName());
        }

        try {
            server.initialize(initialize.build()).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return server;
    }

    public static SmithyLanguageServer initFromRoot(Path root) {
        try {
            LanguageClient client = new StubClient();
            SmithyLanguageServer server = new SmithyLanguageServer();
            server.connect(client);

            server.initialize(new RequestBuilders.Initialize()
                    .workspaceFolder(root.toUri().toString(), "test")
                    .build())
                    .get();

            return server;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
