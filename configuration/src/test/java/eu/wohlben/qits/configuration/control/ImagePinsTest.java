package eu.wohlben.qits.configuration.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.control.ImagePins.Pin;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The map itself, with no database and no Quarkus boot behind it: the two views have to be the same
 * list, and the reported order has to be the one the contract names.
 */
class ImagePinsTest {

  @Test
  void theOrderIsImageThenApplicationThenKey() {
    assertEquals(
        List.of(
            new Pin("qits/project-agent", "qits-projects", "env.QITS_PROJECTS_AGENT_IMAGE_VERSION"),
            new Pin(
                "qits/workspace", "qits-projects", "env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION"),
            new Pin("qits/workspace", "qits-workspaces", "env.QITS_WORKSPACE_IMAGE_VERSION"),
            new Pin("qits/workspace-editor", "qits-workspaces", "env.QITS_EDITOR_IMAGE_VERSION")),
        ImagePins.ORDERED,
        "the answer's order is sorted, not the order the list happens to be typed in");
  }

  /**
   * The two views are one list. A pin the listener would write and the report would not mention —
   * or the other way round — is exactly the disagreement this class exists to make impossible.
   */
  @Test
  void everyPinIsReachableThroughBothViews() {
    assertEquals(
        ImagePins.ORDERED.size(),
        ImagePins.BY_IMAGE.values().stream().mapToInt(List::size).sum(),
        "grouping by image must lose nothing");
    for (Pin pin : ImagePins.ORDERED) {
      assertTrue(
          ImagePins.BY_IMAGE.get(pin.image()).contains(pin),
          () -> pin + " is reported but would never be written");
    }
  }

  /**
   * The match is a whole-name lookup: {@code qits/workspace-editor} opens with {@code
   * qits/workspace} and is its own key, with its own single pin.
   */
  @Test
  void theWorkspaceImageMovesTwoPinsAndTheEditorImageOne() {
    assertEquals(2, ImagePins.BY_IMAGE.get("qits/workspace").size());
    assertEquals(1, ImagePins.BY_IMAGE.get("qits/workspace-editor").size());
    assertNull(ImagePins.BY_IMAGE.get("qits/qits-stt"), "an image we do not pin has no entry");
  }

  @Test
  void theViewsAreImmutable() {
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            ImagePins.BY_IMAGE.put(
                "qits/anything", List.of(new Pin("qits/anything", "app", "env.A"))));
    assertThrows(
        UnsupportedOperationException.class,
        () -> ImagePins.ORDERED.add(new Pin("qits/anything", "app", "env.A")));
  }
}
