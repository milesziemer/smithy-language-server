/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static software.amazon.smithy.lsp.UtilMatchers.anOptionalOf;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.ServerState;
import software.amazon.smithy.lsp.SmithyMatchers;
import software.amazon.smithy.lsp.TestWorkspace;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.traits.LengthTrait;
import software.amazon.smithy.model.traits.PatternTrait;
import software.amazon.smithy.model.traits.TagsTrait;
import software.amazon.smithy.model.validation.ValidatedResult;

public class ProjectTest {
    @Test
    public void changeFileApplyingSimpleTrait() {
        String m1 = """
                $version: "2"
                namespace com.foo
                string Foo
                apply Bar @length(min: 1)
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2);
        Project project = load(workspace.getRoot());

        Shape bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getProjectFile(uri).document();
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));
    }

    @Test
    public void changeFileApplyingListTrait() {
        String m1 = """
                $version: "2"
                namespace com.foo
                string Foo
                apply Bar @tags(["foo"])
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2);
        Project project = load(workspace.getRoot());

        Shape bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getProjectFile(uri).document();
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
    }

    @Test
    public void changeFileApplyingListTraitWithUnrelatedDependencies() {
        String m1 = """
                $version: "2"
                namespace com.foo
                string Foo
                apply Bar @tags(["foo"])
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                string Baz
                """;
        String m3 = """
                $version: "2"
                namespace com.foo
                apply Baz @length(min: 1)
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = load(workspace.getRoot());

        Shape bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        Shape baz = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Baz"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(baz.hasTrait("length"), is(true));
        assertThat(baz.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getProjectFile(uri).document();
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        baz = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Baz"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(baz.hasTrait("length"), is(true));
        assertThat(baz.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));
    }

    @Test
    public void changingFileApplyingListTraitWithRelatedDependencies() {
        String m1 = """
                $version: "2"
                namespace com.foo
                string Foo
                apply Bar @tags(["foo"])
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                """;
        String m3 = """
                $version: "2"
                namespace com.foo
                apply Bar @length(min: 1)
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = load(workspace.getRoot());

        Shape bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getProjectFile(uri).document();
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));
    }

    @Test
    public void changingFileApplyingListTraitWithRelatedArrayTraitDependencies() {
        String m1 = """
                $version: "2"
                namespace com.foo
                string Foo
                apply Bar @tags(["foo"])
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                """;
        String m3 = """
                $version: "2"
                namespace com.foo
                apply Bar @tags(["bar"])
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = load(workspace.getRoot());

        Shape bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo", "bar"));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getProjectFile(uri).document();
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo", "bar"));
    }

    @Test
    public void changingFileWithDependencies() {
        String m1 = """
                $version: "2"
                namespace com.foo
                string Foo
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                apply Foo @length(min: 1)
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2);
        Project project = load(workspace.getRoot());

        Shape foo = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("length"), is(true));
        assertThat(foo.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getProjectFile(uri).document();
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        foo = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("length"), is(true));
        assertThat(foo.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));
    }

    @Test
    public void changingFileWithArrayDependencies() {
        String m1 = """
                $version: "2"
                namespace com.foo
                string Foo
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                apply Foo @tags(["foo"])
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2);
        Project project = load(workspace.getRoot());

        Shape foo = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getProjectFile(uri).document();
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        foo = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
    }

    @Test
    public void changingFileWithMixedArrayDependencies() {
        String m1 = """
                $version: "2"
                namespace com.foo
                @tags(["foo"])
                string Foo
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                apply Foo @tags(["foo"])
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2);
        Project project = load(workspace.getRoot());

        Shape foo = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo", "foo"));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getProjectFile(uri).document();
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        foo = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo", "foo"));
    }

    @Test
    public void changingFileWithArrayDependenciesWithDependencies() {
        String m1 = """
                $version: "2"
                namespace com.foo
                string Foo
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                apply Foo @tags(["foo"])
                """;
        String m3 = """
                $version: "2"
                namespace com.foo
                apply Bar @length(min: 1)
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = load(workspace.getRoot());

        Shape foo = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        Shape bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getProjectFile(uri).document();
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        foo = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));
    }

    @Test
    public void removingSimpleApply() {
        String m1 = """
                $version: "2"
                namespace com.foo
                apply Bar @length(min: 1)
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                """;
        String m3 = """
                $version: "2"
                namespace com.foo
                apply Bar @pattern("a")
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = load(workspace.getRoot());

        Shape bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("pattern"), is(true));
        assertThat(bar.expectTrait(PatternTrait.class).getPattern().pattern(), equalTo("a"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getProjectFile(uri).document();
        document.applyEdit(LspAdapter.lineSpan(2, 0, document.lineEnd(2)), "");

        project.updateModelWithoutValidating(uri);

        bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("pattern"), is(true));
        assertThat(bar.expectTrait(PatternTrait.class).getPattern().pattern(), equalTo("a"));
        assertThat(bar.hasTrait("length"), is(false));
    }

    @Test
    public void removingArrayApply() {
        String m1 = """
                $version: "2"
                namespace com.foo
                apply Bar @tags(["foo"])
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                """;
        String m3 = """
                $version: "2"
                namespace com.foo
                apply Bar @tags(["bar"])
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = load(workspace.getRoot());

        Shape bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo", "bar"));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getProjectFile(uri).document();
        document.applyEdit(LspAdapter.lineSpan(2, 0, document.lineEnd(2)), "");

        project.updateModelWithoutValidating(uri);

        bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("bar"));
    }

    @Test
    public void loadsEmptyProjectWhenThereAreNoConfigFiles() throws Exception {
        Path root = Files.createTempDirectory("foo");
        Project project = load(root);

        assertThat(project.type(), equalTo(Project.Type.EMPTY));
    }

    @Test
    public void changingTraitWithSourceLocationNone() {
        // Manually construct a Project with a model containing a trait with SourceLocation.NONE,
        // since this test can't rely on any specific trait always having SourceLocation.NONE, as
        // it may be fixed upstream.
        Path root = Path.of("foo").toAbsolutePath();
        String fooPath = root.resolve("foo.smithy").toString();
        SmithyFile fooSmithyFile = SmithyFile.create(fooPath, Document.of("""
                $version: "2"
                namespace com.foo
                @length(max: 10)
                string Foo
                """));
        Map<String, SmithyFile> smithyFiles = new HashMap<>();
        smithyFiles.put(fooPath, fooSmithyFile);
        Model model = Model.builder()
                .addShape(StringShape.builder()
                        .id("com.foo#Foo")
                        .source(fooPath, 4, 1)
                        .addTrait(LengthTrait.builder()
                                .sourceLocation(SourceLocation.NONE)
                                .min(1L)
                                .build())
                        .build())
                .build();
        ValidatedResult<Model> modelResult = ValidatedResult.fromValue(model);
        Project.RebuildIndex rebuildIndex = Project.RebuildIndex.create(modelResult);

        Project project = new Project(
                root,
                ProjectConfig.empty(),
                BuildFiles.of(List.of()),
                smithyFiles,
                Model::assembler,
                Project.Type.DETACHED,
                modelResult,
                rebuildIndex,
                List.of()
        );

        assertThat(project.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.foo#Foo")));
        assertThat(project.modelResult().getValidationEvents(), empty());

        String fooUri = LspAdapter.toUri(fooPath);
        project.updateModelWithoutValidating(fooUri);

        assertThat(project.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.foo#Foo")));
        assertThat(project.modelResult().getValidationEvents(), empty());
    }

    public static Project load(Path root) {
        try {
            return ProjectLoader.load(root, new ServerState());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Path toPath(URL url) {
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
