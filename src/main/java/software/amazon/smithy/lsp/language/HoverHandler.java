/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import software.amazon.smithy.lsp.document.DocumentId;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.syntax.NodeCursor;
import software.amazon.smithy.lsp.syntax.StatementView;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.SmithyIdlModelSerializer;
import software.amazon.smithy.model.traits.DocumentationTrait;
import software.amazon.smithy.model.traits.IdRefTrait;
import software.amazon.smithy.model.traits.StringTrait;
import software.amazon.smithy.model.validation.ValidatedResult;

/**
 * Handles hover requests for the Smithy IDL.
 */
public final class HoverHandler {
    private final Project project;
    private final IdlFile smithyFile;

    /**
     * @param project Project the hover is in
     * @param smithyFile Smithy file the hover is in
     */
    public HoverHandler(Project project, IdlFile smithyFile) {
        this.project = project;
        this.smithyFile = smithyFile;
    }

    /**
     * @param params The request params
     * @return The hover content
     */
    public Hover handle(HoverParams params) {
        Position position = params.getPosition();
        DocumentId id = smithyFile.document().copyDocumentId(position);
        if (id == null || id.idSlice().isEmpty()) {
            return null;
        }

        Syntax.IdlParseResult parseResult = smithyFile.getParse();
        int documentIndex = smithyFile.document().indexOfPosition(position);
        IdlPosition idlPosition = StatementView.createAt(parseResult, documentIndex)
                .map(IdlPosition::of)
                .orElse(null);

        return switch (idlPosition) {
            case IdlPosition.ControlKey ignored -> Builtins.CONTROL.getMember(id.copyIdValueForElidedMember())
                    .map(HoverHandler::withShapeDocs)
                    .orElse(null);

            case IdlPosition.MetadataKey ignored -> Builtins.METADATA.getMember(id.copyIdValue())
                    .map(HoverHandler::withShapeDocs)
                    .orElse(null);

            case IdlPosition.MetadataValue metadataValue -> takeShapeReference(
                            ShapeSearch.searchMetadataValue(metadataValue))
                    .map(HoverHandler::withShapeDocs)
                    .orElse(null);

            case null -> null;

            default -> modelSensitiveHover(id, idlPosition);
        };
    }

    private static Optional<? extends Shape> takeShapeReference(NodeSearch.Result result) {
        return switch (result) {
            case NodeSearch.Result.TerminalShape(Shape shape, var ignored)
                    when shape.hasTrait(IdRefTrait.class) -> Optional.of(shape);

            case NodeSearch.Result.ObjectKey(NodeCursor.Key key, Shape containerShape, var ignored)
                    when !containerShape.isMapShape() -> containerShape.getMember(key.name());

            default -> Optional.empty();
        };
    }

    private Hover modelSensitiveHover(DocumentId id, IdlPosition idlPosition) {
        ValidatedResult<Model> validatedModel = project.modelResult();
        if (validatedModel.getResult().isEmpty()) {
            return null;
        }

        Model model = validatedModel.getResult().get();
        Optional<? extends Shape> matchingShape = switch (idlPosition) {
            // TODO: Handle resource ids and properties. This only works for mixins right now.
            case IdlPosition.ElidedMember elidedMember ->
                    ShapeSearch.findElidedMemberParent(elidedMember, id, model)
                            .flatMap(shape -> shape.getMember(id.copyIdValueForElidedMember()));

            default -> ShapeSearch.findShapeDefinition(idlPosition, id, model);
        };

        return matchingShape.map(shape -> withShape(shape, model)).orElse(null);
    }

    private Hover withShape(Shape shape, Model model) {
        String serializedShape = switch (shape) {
            case MemberShape memberShape -> serializeMember(memberShape);
            default -> serializeShape(model, shape);
        };

        if (serializedShape == null) {
            return null;
        }

        String hoverContent = String.format("```smithy%n%s%n```", serializedShape);

        // TODO: Add docs to a separate section of the hover content
        // if (shapeToSerialize.hasTrait(DocumentationTrait.class)) {
        //     String docs = shapeToSerialize.expectTrait(DocumentationTrait.class).getValue();
        //     hoverContent.append("\n---\n").append(docs);
        // }

        return withMarkupContents(hoverContent);
    }

    private static Hover withShapeDocs(Shape shape) {
        return shape.getTrait(DocumentationTrait.class)
                .map(StringTrait::getValue)
                .map(HoverHandler::withMarkupContents)
                .orElse(null);
    }

    private static Hover withMarkupContents(String text) {
        return new Hover(new MarkupContent("markdown", text));
    }

    private static String serializeMember(MemberShape memberShape) {
        StringBuilder contents = new StringBuilder();
        contents.append("namespace")
                .append(" ")
                .append(memberShape.getId().getNamespace())
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        for (var trait : memberShape.getAllTraits().values()) {
            if (trait.toShapeId().equals(DocumentationTrait.ID)) {
                continue;
            }

            contents.append("@")
                    .append(trait.toShapeId().getName())
                    .append("(")
                    .append(Node.printJson(trait.toNode()))
                    .append(")")
                    .append(System.lineSeparator());
        }

        contents.append(memberShape.getMemberName())
                .append(": ")
                .append(memberShape.getTarget().getName())
                .append(System.lineSeparator());
        return contents.toString();
    }

    private static String serializeShape(Model model, Shape shape) {
        SmithyIdlModelSerializer serializer = SmithyIdlModelSerializer.builder()
                .metadataFilter(key -> false)
                .shapeFilter(s -> s.getId().equals(shape.getId()))
                // TODO: If we remove the documentation trait in the serializer,
                //  it also gets removed from members. This causes weird behavior if
                //  there are applied traits (such as through mixins), where you get
                //  an empty apply because the documentation trait was removed
                // .traitFilter(trait -> !trait.toShapeId().equals(DocumentationTrait.ID))
                .serializePrelude()
                .build();
        Map<Path, String> serialized = serializer.serialize(model);
        Path path = Paths.get(shape.getId().getNamespace() + ".smithy");
        if (!serialized.containsKey(path)) {
            return null;
        }

        return serialized.get(path)
                .substring(15) // remove '$version: "2.0"'
                .trim()
                .replaceAll(Matcher.quoteReplacement(
                        // Replace newline literals with actual newlines
                        System.lineSeparator() + System.lineSeparator()), System.lineSeparator());
    }
}
