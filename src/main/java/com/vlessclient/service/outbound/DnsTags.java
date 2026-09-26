package com.vlessclient.service.outbound;

/**
 * The tags of the DNS servers the generator emits, in one place, as
 * {@link OutboundTags} holds the outbound ones.
 *
 * <p>They are referenced from {@code dns.final}, every DNS rule's
 * {@code server}, a server's {@code domain_resolver} and
 * {@code route.default_domain_resolver}. A reference that names no server
 * stops the core at startup, whichever server is picked.</p>
 */
public final class DnsTags {

    /** The resolver the tunnel carries: everything not named elsewhere. */
    public static final String PROXY = "proxy-dns";

    /** The resolver outside the tunnel, for names that go direct. */
    public static final String DIRECT = "direct-dns";

    /** The OS resolver: localhost, {@code .local}, and bootstrap lookups. */
    public static final String LOCAL = "local-dns";

    private DnsTags() {
    }
}
