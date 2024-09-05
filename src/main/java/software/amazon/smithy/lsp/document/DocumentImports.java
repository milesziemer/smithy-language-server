/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document;

import java.util.Set;
import org.eclipse.lsp4j.Range;

/**
 * The imports of a document, including the range they occupy.
 *
 * @param importsRange The range of the imports
 * @param imports The set of imported shape ids. They are not guaranteed to be valid shape ids
 */
public record DocumentImports(Range importsRange, Set<String> imports) {}
