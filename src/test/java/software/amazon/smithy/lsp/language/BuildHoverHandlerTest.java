/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.lsp.language;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.RequestBuilders;
import software.amazon.smithy.lsp.TextWithPositions;
import software.amazon.smithy.lsp.project.BuildFile;
import software.amazon.smithy.lsp.project.BuildFileType;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectTest;

public class BuildHoverHandlerTest {
    @Test
    public void includesDocs() {
        var twp = TextWithPositions.from("""
                {
                    %"version": "1.0"
                }
                """);
        var hovers = getHovers(BuildFileType.SMITHY_BUILD, twp);

        assertThat(hovers, contains(containsString("version")));
    }

    @Test
    public void includesExternalDocs() {
        var twp = TextWithPositions.from("""
                {
                    %"projections": {}
                }
                """);
        var hovers = getHovers(BuildFileType.SMITHY_BUILD, twp);

        assertThat(hovers, contains(containsString("https://smithy.io")));
    }

    @Test
    public void nested() {
        var twp = TextWithPositions.from("""
                {
                    "maven": {
                        %"dependencies": []
                    }
                }
                """);
        var hovers = getHovers(BuildFileType.SMITHY_BUILD, twp);

        assertThat(hovers, contains(containsString("coordinates")));
    }

    @Test
    public void noHoverForValues() {
        var twp = TextWithPositions.from("""
                %{
                    "version": %"1.0",
                    "sources": %[]
                }
                """);
        var hovers = getHovers(BuildFileType.SMITHY_BUILD, twp);

        assertThat(hovers, empty());
    }

    private static List<String> getHovers(BuildFileType buildFileType, TextWithPositions twp) {
        try {
            // TODO: Replace with TestWorkspace::emptyWithNoConfig from
            //  https://github.com/smithy-lang/smithy-language-server/pull/199 when
            //  it gets merged.
            Path root = Files.createTempDirectory("test");
            root.toFile().deleteOnExit();

            Path path = root.resolve(buildFileType.filename());
            Files.writeString(path, twp.text());

            Project project = ProjectTest.load(root);
            String uri = path.toUri().toString();
            BuildFile buildFile = (BuildFile) project.getProjectFile(uri);

            List<String> hover = new ArrayList<>();
            BuildHoverHandler handler = new BuildHoverHandler(buildFile);
            for (Position position : twp.positions()) {
                HoverParams params = RequestBuilders.positionRequest()
                        .uri(uri)
                        .position(position)
                        .buildHover();
                Hover result = handler.handle(params);
                if (result != null) {
                    hover.add(result.getContents().getRight().getValue());
                }
            }
            return hover;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
