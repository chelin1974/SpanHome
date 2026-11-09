package io.github.chelin1974.spanhome.span;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * TLS that trusts exactly one panel's self-signed CA, and nothing else.
 *
 * <p>The panel signs its server certificate with a CA it generates itself, and lists its
 * {@code span-<serial>.local} name and every IP address as subject alternative names, so normal
 * hostname verification works against either. Trusting only that CA, rather than adding it to
 * the JVM's store or disabling verification, means a different device answering on the panel's
 * address is refused.
 */
public final class PanelTls {

  private PanelTls() {}

  public static SSLContext sslContext(String caPem) throws Exception {
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    Collection<? extends Certificate> certificates =
        factory.generateCertificates(new ByteArrayInputStream(caPem.getBytes(StandardCharsets.US_ASCII)));
    if (certificates.isEmpty()) {
      throw new IllegalArgumentException("no certificate found in the panel's CA file");
    }

    KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
    trust.load(null, null);
    int index = 0;
    for (Certificate certificate : certificates) {
      trust.setCertificateEntry("span-ca-" + index++, certificate);
    }

    TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    managers.init(trust);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, managers.getTrustManagers(), null);
    return context;
  }
}
