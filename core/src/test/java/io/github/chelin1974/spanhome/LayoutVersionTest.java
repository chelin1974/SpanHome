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

  private static CircuitOutlet outlet(HomieTree tree) {
    return new CircuitOutlet(2, PanelFixture.HOT_TUB, "Hot Tub", PANEL, tree, true, (id, on) -> {}, null);
  }

  private static PowerSensor power(int id, String name) {
    return new PowerSensor(id, name, name, () -> 0, null);
  }

  @Test
  @DisplayName("the configuration number goes up only when the layout changes")
  void bumpsOnChange() throws Exception {
    HomieTree tree = PanelFixture.tree();
    Path file = Files.createTempDirectory("span").resolve("layout.json");

    int first = LayoutVersion.resolve(file, List.of(outlet(tree)));
    assertEquals(2, first, "starts at 2 so an already-paired bridge reloads once");
    assertEquals(first, LayoutVersion.resolve(file, List.of(outlet(tree))), "same layout, same number");

    int withSensor = LayoutVersion.resolve(file, List.of(outlet(tree), power(3, "Hot Tub Power")));
    assertEquals(first + 1, withSensor, "a new accessory changes the layout");

    int renamed = LayoutVersion.resolve(file, List.of(outlet(tree), power(3, "Spa Power")));
    assertEquals(withSensor + 1, renamed, "a renamed accessory changes the layout");
  }
}
