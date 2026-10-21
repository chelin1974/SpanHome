package io.github.chelin1974.spanhome;

import static io.github.chelin1974.spanhome.PanelFixture.PANEL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.chelin1974.spanhome.homekit.CircuitOutlet;
import io.github.chelin1974.spanhome.homekit.LayoutVersion;
import io.github.chelin1974.spanhome.homekit.PowerSensor;
import io.github.chelin1974.spanhome.span.HomieTree;
import io.github.hapjava.accessories.HomekitAccessory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LayoutVersionTest {

  private static CircuitOutlet outlet(HomieTree tree, boolean watts) {
    return new CircuitOutlet(2, PanelFixture.HOT_TUB, "Hot Tub", PANEL, tree, true, (id, on) -> {}, null, watts);
  }

  @Test
  @DisplayName("the configuration number goes up only when the layout changes")
  void bumpsOnChange() throws Exception {
    HomieTree tree = PanelFixture.tree();
    Path file = Files.createTempDirectory("span").resolve("layout.json");

    List<HomekitAccessory> before = List.of(outlet(tree, false));
    int first = LayoutVersion.resolve(file, before);
    assertEquals(2, first, "starts at 2 so an already-paired bridge reloads once");
    assertEquals(first, LayoutVersion.resolve(file, List.of(outlet(tree, false))), "same layout, same number");

    int withWatts = LayoutVersion.resolve(file, List.of(outlet(tree, true)));
    assertEquals(first + 1, withWatts, "a new service changes the layout");

    int withSensor = LayoutVersion.resolve(file, List.of(outlet(tree, true), new PowerSensor(9, "House Power", "h", () -> 0, null)));
    assertEquals(withWatts + 1, withSensor, "a new accessory changes the layout");
  }
}
