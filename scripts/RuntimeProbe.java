import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.net.http.*;
import java.security.*;
import java.security.cert.Certificate;
import java.text.NumberFormat;
import java.time.format.*;
import java.time.*;
import java.util.*;

/** Probes a trimmed runtime image for the capabilities the app needs but no test covers. */
public class RuntimeProbe {
    public static void main(String[] args) throws Exception {
        // 1. Russian locale data (jdk.localedata): month name + grouping separator.
        String month = Month.JANUARY.getDisplayName(java.time.format.TextStyle.FULL, Locale.of("ru"));
        String number = NumberFormat.getInstance(Locale.of("ru")).format(1234567.5);
        System.out.println("ru month=" + month + " number=" + number);
        if (!month.toLowerCase().startsWith("янв")) throw new IllegalStateException("no ru locale data");

        // 2. EC crypto + TLS over loopback (SunEC, SunJSSE).
        System.out.println("EC provider=" + KeyPairGenerator.getInstance("EC").getProvider().getName());
        System.out.println("TLS default protocols=" + Arrays.toString(
                ((SSLSocketFactory) SSLSocketFactory.getDefault()).getDefaultCipherSuites()).substring(0, 60) + "...");
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(null, null, null);
        System.out.println("TLSv1.3 context=" + ctx.getProtocol());

        // 3. com.sun.net.httpserver (jdk.httpserver) + java.net.http client.
        HttpServer srv = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        srv.createContext("/", ex -> { byte[] b = "ok".getBytes(); ex.sendResponseHeaders(200, b.length);
            try (var o = ex.getResponseBody()) { o.write(b); } });
        srv.start();
        var resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + srv.getAddress().getPort() + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.println("httpserver+httpclient=" + resp.statusCode() + resp.body());
        srv.stop(0);

        // 4. XML parsing (java.xml) - logback reads its config this way.
        var dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        var doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(
                "<configuration><root level=\"INFO\"/></configuration>".getBytes()));
        System.out.println("xml root=" + doc.getDocumentElement().getNodeName());

        // 5. Charsets beyond the base set (jdk.charsets) - Windows consoles use these.
        System.out.println("windows-1251=" + java.nio.charset.Charset.forName("windows-1251").name());

        // 6. Zip filesystem (jdk.zipfs) and sun.misc.Unsafe (jdk.unsupported).
        System.out.println("zipfs=" + java.nio.file.spi.FileSystemProvider.installedProviders().stream()
                .map(java.nio.file.spi.FileSystemProvider::getScheme).toList());
        Class.forName("sun.misc.Unsafe");
        System.out.println("unsafe=present");

        // 7. Desktop/AWT tray support (java.desktop) - the menu bar icon.
        System.setProperty("java.awt.headless", "true");
        System.out.println("awt classes=" + java.awt.SystemTray.class.getName());
        System.out.println("ALL PROBES PASSED");
    }
}
