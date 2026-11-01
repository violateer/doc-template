package io.github.violateer.doctemplate.core.datasource;

import java.util.Map;

/**
 * Implement in the host application to expose business data to document templates.
 *
 * <p>This is the only type a host needs in order to plug in a {@code JAVA} data block, and it is
 * intentionally dependency-free: a module that only implements a provider can depend on
 * {@code doc-template-core} alone, without pulling in Spring, MyBatis-Plus or POI.</p>
 *
 * <p>{@code params} is the sole input. It is assembled from the block's {@code param_config}
 * mapping (literal values plus {@code $request.*} references) merged with the generate request
 * params. Row-level filtering — "only the caller's own orders", for example — is therefore driven
 * entirely by values passed in {@code params}; the provider receives no separate caller identity.</p>
 */
public interface DocumentDataProvider {
    /** Stable identifier selected in a template data-block configuration. */
    String getCode();

    /** Execute using the resolved block parameters. */
    Object execute(Map<String, Object> params);
}
