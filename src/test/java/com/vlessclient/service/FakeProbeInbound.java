package com.vlessclient.service;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for the SOCKS5 side of the core's probe inbound. It asks for this
 * run's password, notes each CONNECT, and answers it the way the core does
 * once the tunnel has carried the connection or failed to. After a success it
 * can answer one HTTP request, as the target at the far end would.
 */
final class FakeProbeInbound implements AutoCloseable {

    /** The core's reply when the tunnel carried the connection. */
    static final int CARRIED = 0;

    /** The core's reply when the far end refused the connection. */
    static final int REFUSED = 5;

    /** Each CONNECT, as "{address type} {host}:{port}". */
    final List<String> connects = new CopyOnWriteArrayList<>();

    /** Each user name and password offered, as "user:password". */
    final List<String> logins = new CopyOnWriteArrayList<>();

    /** The head of each HTTP request that followed a CONNECT. */
    final List<String> requests = new CopyOnWriteArrayList<>();

    private final ServerSocket server;
    private final int reply;
    private final String httpAnswer;
    private final int relayPort;

    /**
     * Starts listening on a free loopback port.
     *
     * @param reply      the reply to every CONNECT, {@link #CARRIED} or a SOCKS error
     * @param httpAnswer the status line to answer an HTTP request with, or null
     */
    FakeProbeInbound(int reply, String httpAnswer) throws IOException {
        this(reply, httpAnswer, 0);
    }

    private FakeProbeInbound(int reply, String httpAnswer, int relayPort) throws IOException {
        this.reply = reply;
        this.httpAnswer = httpAnswer;
        this.relayPort = relayPort;
        server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(this::serve, "fake-probe-inbound");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * One that carries every connection to a server on the loopback, whatever
     * the CONNECT named, the way a working tunnel carries it to the site.
     *
     * @param port the loopback port of the server
     */
    static FakeProbeInbound relayingTo(int port) throws IOException {
        return new FakeProbeInbound(CARRIED, null, port);
    }

    int port() {
        return server.getLocalPort();
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket client = server.accept()) {
                client.setSoTimeout(5000);
                answer(client);
            } catch (IOException e) {
                // A client that hung up halfway, or the server closing.
            }
        }
    }

    private void answer(Socket client) throws IOException {
        DataInputStream in = new DataInputStream(client.getInputStream());
        OutputStream out = client.getOutputStream();
        in.readUnsignedByte();                       // version
        in.readNBytes(in.readUnsignedByte());        // the methods offered
        out.write(new byte[] {5, 2});                // a password, as the inbound asks
        out.flush();
        in.readUnsignedByte();                       // the password exchange's version
        String user = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.UTF_8);
        String password = new String(in.readNBytes(in.readUnsignedByte()),
                StandardCharsets.UTF_8);
        logins.add(user + ":" + password);
        boolean known = user.equals(LocalProxyCredentials.username())
                && password.equals(LocalProxyCredentials.password());
        out.write(new byte[] {1, (byte) (known ? 0 : 1)});
        out.flush();
        if (!known) {
            return;
        }

        in.readNBytes(3);                            // version, command, reserved
        int type = in.readUnsignedByte();
        String host = switch (type) {
            case 1 -> InetAddress.getByAddress(in.readNBytes(4)).getHostAddress();
            case 4 -> InetAddress.getByAddress(in.readNBytes(16)).getHostAddress();
            default -> new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.UTF_8);
        };
        connects.add(type + " " + host + ":" + in.readUnsignedShort());
        out.write(new byte[] {5, (byte) reply, 0, 1, 127, 0, 0, 1, 0, 0});
        out.flush();
        if (reply == CARRIED && relayPort > 0) {
            relay(in, out);
            return;
        }
        if (reply != CARRIED || httpAnswer == null) {
            return;
        }

        BufferedReader lines = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.US_ASCII));
        StringBuilder head = new StringBuilder();
        for (String line = lines.readLine(); line != null && !line.isEmpty();
                line = lines.readLine()) {
            head.append(line).append('\n');
        }
        requests.add(head.toString());
        out.write((httpAnswer + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Copies bytes both ways between the client and the server until the server is done. */
    private void relay(InputStream fromClient, OutputStream toClient) throws IOException {
        try (Socket site = new Socket(InetAddress.getLoopbackAddress(), relayPort)) {
            OutputStream toSite = site.getOutputStream();
            Thread upstream = Thread.ofVirtual().start(() -> {
                try {
                    fromClient.transferTo(toSite);
                } catch (IOException clientGone) {
                    // The client hung up; the server's side ends the relay.
                }
            });
            site.getInputStream().transferTo(toClient);
            upstream.interrupt();
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
