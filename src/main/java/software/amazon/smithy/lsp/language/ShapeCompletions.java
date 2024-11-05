/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemLabelDetails;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.lsp.document.DocumentImports;
import software.amazon.smithy.lsp.document.DocumentNamespace;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.traits.MixinTrait;
import software.amazon.smithy.model.traits.RequiredTrait;
import software.amazon.smithy.model.traits.TraitDefinition;

/**
 * Maps {@link CompletionCandidates.Shapes} to {@link CompletionItem}s.
 */
final class ShapeCompletions {
    private final Model model;
    private final SmithyFile smithyFile;
    private final Matcher matcher;
    private final Mapper mapper;

    private ShapeCompletions(Model model, SmithyFile smithyFile, Matcher matcher, Mapper mapper) {
        this.model = model;
        this.smithyFile = smithyFile;
        this.matcher = matcher;
        this.mapper = mapper;
    }

    List<CompletionItem> getCompletionItems(CompletionCandidates.Shapes candidates) {
        return streamShapes(candidates)
                .filter(matcher::test)
                .mapMulti(mapper::accept)
                .toList();
    }

    private Stream<? extends Shape> streamShapes(CompletionCandidates.Shapes candidates) {
        return switch (candidates) {
            case ANY_SHAPE -> model.shapes();
            case STRING_SHAPES -> model.getStringShapes().stream();
            case RESOURCE_SHAPES -> model.getResourceShapes().stream();
            case OPERATION_SHAPES -> model.getOperationShapes().stream();
            case ERROR_SHAPES -> model.getShapesWithTrait(ErrorTrait.class).stream();
            case TRAITS -> model.getShapesWithTrait(TraitDefinition.class).stream();
            case MIXINS -> model.getShapesWithTrait(MixinTrait.class).stream();
            case MEMBER_TARGETABLE -> model.shapes()
                    .filter(shape -> !shape.isMemberShape()
                                     && !shape.hasTrait(TraitDefinition.ID)
                                     && !shape.hasTrait(MixinTrait.ID));
            case USE_TARGET -> model.shapes()
                    .filter(shape -> !shape.isMemberShape()
                                     && !shape.getId().getNamespace().contentEquals(smithyFile.namespace())
                                     && !smithyFile.hasImport(shape.getId().toString()));
        };
    }

    static ShapeCompletions create(
            IdlPosition idlPosition,
            Model model,
            String matchToken,
            Range insertRange
    ) {
        AddItems addItems = AddItems.DEFAULT;
        ModifyItems modifyItems = ModifyItems.DEFAULT;

        if (idlPosition instanceof IdlPosition.TraitId) {
            addItems = new AddDeepTraitBodyItem(model);
        }

        ToLabel toLabel;
        if (shouldMatchFullId(idlPosition, matchToken)) {
            toLabel = (shape) -> shape.getId().toString();
        } else {
            toLabel = (shape) -> shape.getId().getName();
            modifyItems = new AddImportTextEdits(idlPosition.smithyFile());
        }

        Matcher matcher = new Matcher(matchToken, toLabel, idlPosition.smithyFile());
        Mapper mapper = new Mapper(insertRange, toLabel, addItems, modifyItems);
        return new ShapeCompletions(model, idlPosition.smithyFile(), matcher, mapper);
    }

    private static boolean shouldMatchFullId(IdlPosition idlPosition, String matchToken) {
        return idlPosition instanceof IdlPosition.UseTarget
               || matchToken.contains("#")
               || matchToken.contains(".");
    }

    /**
     * Filters shape candidates based on whether they are accessible and match
     * the match token.
     *
     * @param matchToken The token to match shapes against, i.e. the token
     *                   being typed.
     * @param toLabel The way to get the label to match against from a shape.
     * @param smithyFile The current Smithy file.
     */
    private record Matcher(String matchToken, ToLabel toLabel, SmithyFile smithyFile) {
        boolean test(Shape shape) {
            return smithyFile.isAccessible(shape) && toLabel.toLabel(shape).toLowerCase().startsWith(matchToken);
        }
    }

    /**
     * Maps matching shape candidates to {@link CompletionItem}.
     *
     * @param insertRange Range the completion text will be inserted into.
     * @param toLabel The way to get the label to show in the completion item.
     * @param addItems Adds extra completion items for a shape.
     * @param modifyItems Modifies created completion items for a shape.
     */
    private record Mapper(Range insertRange, ToLabel toLabel, AddItems addItems, ModifyItems modifyItems) {
        void accept(Shape shape, Consumer<CompletionItem> completionItemConsumer) {
            String shapeLabel = toLabel.toLabel(shape);
            CompletionItem defaultItem = shapeCompletion(shapeLabel, shape);
            completionItemConsumer.accept(defaultItem);
            addItems.add(this, shapeLabel, shape, completionItemConsumer);
        }

        private CompletionItem shapeCompletion(String shapeLabel, Shape shape) {
            var completionItem = new CompletionItem(shapeLabel);
            completionItem.setKind(CompletionItemKind.Class);
            completionItem.setDetail(shape.getType().toString());

            var labelDetails = new CompletionItemLabelDetails();
            labelDetails.setDetail(shape.getId().getNamespace());
            completionItem.setLabelDetails(labelDetails);

            TextEdit edit = new TextEdit(insertRange, shapeLabel);
            completionItem.setTextEdit(Either.forLeft(edit));

            modifyItems.modify(this, shapeLabel, shape, completionItem);
            return completionItem;
        }
    }

    /**
     * Strategy to get the completion label from {@link Shape}s used for
     * matching and constructing the completion item.
     */
    private interface ToLabel {
        String toLabel(Shape shape);
    }

    /**
     * A customization point for adding extra completions items for a given
     * shape.
     */
    private interface AddItems {
        AddItems DEFAULT = new AddItems() {
        };

        default void add(Mapper mapper, String shapeLabel, Shape shape, Consumer<CompletionItem> consumer) {
        }
    }

    /**
     * Adds a completion item that fills out required member names.
     *
     * TODO: Need to check what happens for recursive traits. The model won't
     *  be valid, but it may still be loaded and could blow this up.
     */
    private static final class AddDeepTraitBodyItem extends ShapeVisitor.Default<String> implements AddItems {
        private final Model model;

        AddDeepTraitBodyItem(Model model) {
            this.model = model;
        }

        @Override
        public void add(Mapper mapper, String shapeLabel, Shape shape, Consumer<CompletionItem> consumer) {
            String traitBody = shape.accept(this);
            // Strip outside pair of brackets from any structure traits.
            if (!traitBody.isEmpty() && traitBody.charAt(0) == '{') {
                traitBody = traitBody.substring(1, traitBody.length() - 1);
            }

            if (!traitBody.isEmpty()) {
                String label = String.format("%s(%s)", shapeLabel, traitBody);
                var traitWithMembersItem = mapper.shapeCompletion(label, shape);
                consumer.accept(traitWithMembersItem);
            }
        }

        @Override
        protected String getDefault(Shape shape) {
            return CompletionCandidates.defaultCandidates(shape).value();
        }

        @Override
        public String structureShape(StructureShape shape) {
            List<String> entries = new ArrayList<>();
            for (MemberShape memberShape : shape.members()) {
                if (memberShape.hasTrait(RequiredTrait.class)) {
                    entries.add(memberShape.getMemberName() + ": " + memberShape.accept(this));
                }
            }
            return "{" + String.join(", ", entries) + "}";
        }

        @Override
        public String memberShape(MemberShape shape) {
            return model.getShape(shape.getTarget())
                    .map(target -> target.accept(this))
                    .orElse("");
        }
    }

    /**
     * A customization point for modifying created completion items, adding
     * context, additional text edits, etc.
     */
    private interface ModifyItems {
        ModifyItems DEFAULT = new ModifyItems() {
        };

        default void modify(Mapper mapper, String shapeLabel, Shape shape, CompletionItem completionItem) {
        }
    }

    /**
     * Adds text edits for use statements for shapes that need to be imported.
     */
    private static final class AddImportTextEdits implements ModifyItems {
        private final SmithyFile smithyFile;

        AddImportTextEdits(SmithyFile smithyFile) {
            this.smithyFile = smithyFile;
        }

        @Override
        public void modify(Mapper mapper, String shapeLabel, Shape shape, CompletionItem completionItem) {
            if (smithyFile.inScope(shape.getId())) {
                return;
            }

            // We can only know where to put the import if there's already use statements, or a namespace
            smithyFile.documentImports().map(DocumentImports::importsRange)
                    .or(() -> smithyFile.documentNamespace().map(DocumentNamespace::statementRange))
                    .ifPresent(range -> {
                        Range editRange = LspAdapter.point(range.getEnd());
                        String insertText = System.lineSeparator() + "use " + shape.getId().toString();
                        TextEdit importEdit = new TextEdit(editRange, insertText);
                        completionItem.setAdditionalTextEdits(List.of(importEdit));
                    });
        }
    }
}
