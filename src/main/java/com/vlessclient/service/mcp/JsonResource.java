package com.vlessclient.service.mcp;

import java.util.function.Supplier;

/**
 * An {@link McpResource} whose content is produced by a {@link Supplier} and
 * serialized as JSON. Lets resources be declared as one-liners over the
 * {@link AppControlService}.
 */
public class JsonResource implements McpResource {

    private final String uri;
    private final String name;
    private final String description;
    private final Supplier<Object> supplier;

    /**
     * Creates a JSON resource backed by the given supplier.
     *
     * @param uri the resource URI
     * @param name the human-readable resource name
     * @param description the resource description
     * @param supplier the source of the resource content
     */
    public JsonResource(String uri, String name, String description, Supplier<Object> supplier) {
        this.uri = uri;
        this.name = name;
        this.description = description;
        this.supplier = supplier;
    }

    @Override
    public String uri() {
        return uri;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Object read() {
        return supplier.get();
    }
}
