/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.lsp4j.Position;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.document.DocumentImports;
import software.amazon.smithy.lsp.document.DocumentNamespace;
import software.amazon.smithy.lsp.document.DocumentShape;
import software.amazon.smithy.lsp.document.DocumentVersion;
import software.amazon.smithy.model.shapes.Shape;

/**
 * The language server's representation of a Smithy file.
 *
 * <p><b>Note:</b> This currently is only ever a .smithy file, but could represent
 * a .json file in the future.
 */
public final class SmithyFile {
    private final String path;
    private final Document document;
    private final Set<Shape> shapes;
    private final DocumentNamespace namespace;
    private final DocumentImports imports;
    private final Map<Position, DocumentShape> documentShapes;
    private final DocumentVersion documentVersion;

    private SmithyFile(Builder builder) {
        this.path = builder.path;
        this.document = builder.document;
        this.shapes = builder.shapes;
        this.namespace = builder.namespace;
        this.imports = builder.imports;
        this.documentShapes = builder.documentShapes;
        this.documentVersion = builder.documentVersion;
    }

    /**
     * @return The path of this Smithy file
     */
    public String getPath() {
        return path;
    }

    /**
     * @return The {@link Document} backing this Smithy file
     */
    public Document getDocument() {
        return document;
    }

    /**
     * @return The Shapes defined in this Smithy file
     */
    public Set<Shape> getShapes() {
        return shapes;
    }

    /**
     * @return This Smithy file's imports, if they exist
     */
    public Optional<DocumentImports> getDocumentImports() {
        return Optional.ofNullable(this.imports);
    }

    /**
     * @return The ids of shapes imported into this Smithy file
     */
    public Set<String> getImports() {
        return getDocumentImports()
                .map(DocumentImports::imports)
                .orElse(Collections.emptySet());
    }

    /**
     * @return This Smithy file's namespace, if one exists
     */
    public Optional<DocumentNamespace> getDocumentNamespace() {
        return Optional.ofNullable(namespace);
    }

    /**
     * @return The shapes in this Smithy file, including referenced shapes
     */
    public Collection<DocumentShape> getDocumentShapes() {
        if (documentShapes == null) {
            return Collections.emptyList();
        }
        return documentShapes.values();
    }

    /**
     * @return A map of {@link Position} to the {@link DocumentShape} they are
     *  the starting position of
     */
    public Map<Position, DocumentShape> getDocumentShapesByStartPosition() {
        if (documentShapes == null) {
            return Collections.emptyMap();
        }
        return documentShapes;
    }

    /**
     * @return The string literal namespace of this Smithy file, or an empty string
     */
    public CharSequence getNamespace() {
        return getDocumentNamespace()
                .map(DocumentNamespace::namespace)
                .orElse("");
    }

    /**
     * @return This Smithy file's version, if it exists
     */
    public Optional<DocumentVersion> getDocumentVersion() {
        return Optional.ofNullable(documentVersion);
    }

    /**
     * @param shapeId The shape id to check
     * @return Whether {@code shapeId} is in this SmithyFile's imports
     */
    public boolean hasImport(String shapeId) {
        if (imports == null) {
            return false;
        }
        return imports.imports().contains(shapeId);
    }

    /**
     * @return A {@link SmithyFile} builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String path;
        private Document document;
        private Set<Shape> shapes;
        private DocumentNamespace namespace;
        private DocumentImports imports;
        private Map<Position, DocumentShape> documentShapes;
        private DocumentVersion documentVersion;

        private Builder() {
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder document(Document document) {
            this.document = document;
            return this;
        }

        public Builder shapes(Set<Shape> shapes) {
            this.shapes = shapes;
            return this;
        }

        public Builder namespace(DocumentNamespace namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder imports(DocumentImports imports) {
            this.imports = imports;
            return this;
        }

        public Builder documentShapes(Map<Position, DocumentShape> documentShapes) {
            this.documentShapes = documentShapes;
            return this;
        }

        public Builder documentVersion(DocumentVersion documentVersion) {
            this.documentVersion = documentVersion;
            return this;
        }

        public SmithyFile build() {
            return new SmithyFile(this);
        }
    }
}
