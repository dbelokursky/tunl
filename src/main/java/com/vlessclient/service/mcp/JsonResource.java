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
