package io.github.chelin1974.spanhome.span;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A live MQTT connection to one panel's broker, feeding a {@link HomieTree}.
 *
 * <p>Subscribes to {@code ebus/5/#}; the panel republishes its whole retained tree on
 * subscribe, which is how discovery works. Reconnects on its own after a network drop or a
 * panel reboot, and resubscribes, since the session is clean.
 *
 * <p>Uses the asynchronous Paho client on purpose: subscribing from inside the connect callback
 * with the blocking client can deadlock.
 *
 * <p>Writes go only to {@code /set} topics, the only ones the broker accepts from clients. No
 * Last Will is set: the broker discards one on a SPAN topic anyway.
 */
public final class SpanPanelConnection implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SpanPanelConnection.class);

  private final PanelCredentials.Panel panel;
  private final HomieTree tree;
  private final List<Runnable> connectivityListeners = new CopyOnWriteArrayList<>();
  private MqttAsyncClient client;
  private volatile boolean connected;

  public SpanPanelConnection(PanelCredentials.Panel panel) {
    this.panel = panel;
    this.tree = new HomieTree(panel.serial);
  }

  public HomieTree tree() {
    return tree;
  }

  public String serial() {
    return panel.serial;
  }

  public boolean isConnected() {
    return connected;
  }

  /** Called whenever the connection drops or comes back. */
  public void onConnectivityChange(Runnable listener) {
    connectivityListeners.add(listener);
  }

  /** Connects and blocks until the first connection succeeds or fails. */
  public void connect() throws MqttException {
    String uri = "ssl://" + panel.host + ":" + panel.mqttsPort;
    String clientId = "span-home-" + panel.serial + "-" + HexFormat.of().formatHex(randomBytes(3));
    client = new MqttAsyncClient(uri, clientId, new MemoryPersistence());

    MqttConnectOptions options = new MqttConnectOptions();
    options.setUserName(panel.brokerUsername);
    options.setPassword(panel.brokerPassword.toCharArray());
    options.setCleanSession(true);
    options.setAutomaticReconnect(true);
    options.setKeepAliveInterval(30);
    options.setConnectionTimeout(20);
    options.setMaxInflight(50);
    options.setHttpsHostnameVerificationEnabled(true);
    try {
      options.setSocketFactory(PanelTls.sslContext(panel.caCertificate).getSocketFactory());
    } catch (Exception e) {
      throw new MqttException(MqttException.REASON_CODE_SSL_CONFIG_ERROR, e);
    }

    client.setCallback(
        new MqttCallbackExtended() {
          @Override
          public void connectComplete(boolean reconnect, String serverURI) {
            connected = true;
            log.info("{} connected to panel {} at {}", reconnect ? "re" : "", panel.serial, serverURI);
            try {
              client.subscribe("ebus/5/#", 0);
            } catch (MqttException e) {
              log.error("could not subscribe to panel {}: {}", panel.serial, e.getMessage());
            }
            connectivityListeners.forEach(SpanPanelConnection::runQuietly);
          }

          @Override
          public void connectionLost(Throwable cause) {
            connected = false;
            log.warn(
                "lost the connection to panel {}: {}. Reconnecting.",
                panel.serial,
                cause == null ? "unknown cause" : cause.getMessage());
            connectivityListeners.forEach(SpanPanelConnection::runQuietly);
          }

          @Override
          public void messageArrived(String topic, MqttMessage message) {
            tree.accept(topic, new String(message.getPayload(), StandardCharsets.UTF_8));
          }

          @Override
          public void deliveryComplete(IMqttDeliveryToken token) {
            // Nothing to do: the panel confirms a change by republishing the property.
          }
        });

    client.connect(options).waitForCompletion(Duration.ofSeconds(30).toMillis());
  }

  /**
   * Waits until the panel has described every device in its tree.
   *
   * @return false on timeout; the caller decides whether a partial tree is usable
   */
  public boolean awaitDiscovery(Duration timeout) throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    // Descriptions trickle in after the subscription; give stragglers a moment once complete.
    while (Instant.now().isBefore(deadline)) {
      if (tree.isDiscoveryComplete() && tree.state(tree.rootId()).isPresent()) {
        Thread.sleep(1_500);
        if (tree.isDiscoveryComplete()) {
          return true;
        }
      }
      Thread.sleep(250);
    }
    return false;
  }

  /** Publishes a Homie command: {@code ebus/5/<device>/<node>/<property>/set}. */
  public void set(String deviceId, String node, String property, String value) throws MqttException {
    if (client == null || !client.isConnected()) {
      throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
    }
    String topic = "ebus/5/" + deviceId + "/" + node + "/" + property + "/set";
    MqttMessage message = new MqttMessage(value.getBytes(StandardCharsets.UTF_8));
    message.setQos(1);
    message.setRetained(false);
    client.publish(topic, message);
  }

  @Override
  public void close() {
    if (client == null) {
      return;
    }
    try {
      if (client.isConnected()) {
        client.disconnect().waitForCompletion(5_000);
      }
      client.close();
    } catch (MqttException e) {
      log.debug("error closing the connection to panel {}: {}", panel.serial, e.getMessage());
    }
  }

  private static void runQuietly(Runnable runnable) {
    try {
      runnable.run();
    } catch (RuntimeException ignored) {
      // A listener bug must not break the MQTT callback thread.
    }
  }

  private static byte[] randomBytes(int count) {
    byte[] bytes = new byte[count];
    new SecureRandom().nextBytes(bytes);
    return bytes;
  }
}
