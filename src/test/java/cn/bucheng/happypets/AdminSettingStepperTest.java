package cn.bucheng.happypets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

class AdminSettingStepperTest {
    private final PetSettings settings = SettingsFixtures.settings();

    @Test
    void leftAndShiftClicksIncreaseFloatSettingsByExpectedSteps() {
        assertEquals(1.19, (double) AdminSettingStepper.next("growth.xp-factor", 1.18, ClickType.LEFT, settings), 0.000001);
        assertEquals(1.28, (double) AdminSettingStepper.next("growth.xp-factor", 1.18, ClickType.SHIFT_LEFT, settings), 0.000001);
    }

    @Test
    void rightClicksCannotMoveValidatedSettingsBelowTheirSupportedRange() {
        assertEquals(1.0, (double) AdminSettingStepper.next("growth.xp-factor", 1.0, ClickType.RIGHT, settings), 0.000001);
        assertEquals(5, AdminSettingStepper.next("performance.autosave-seconds", 5, ClickType.SHIFT_RIGHT, settings));
    }

    @Test
    void changesRespectDependentMaturityAndFollowDistanceLimits() {
        assertEquals(settings.maxLevel(), AdminSettingStepper.next("growth.adult-level", settings.maxLevel(), ClickType.LEFT, settings));
        assertEquals(23.0, (double) AdminSettingStepper.next("follow.distance", 23.0, ClickType.SHIFT_LEFT, settings), 0.000001);
    }

    @Test
    void booleansToggleForEitherMouseButton() {
        assertEquals(false, AdminSettingStepper.next("games.enabled", true, ClickType.RIGHT, settings));
    }
}
