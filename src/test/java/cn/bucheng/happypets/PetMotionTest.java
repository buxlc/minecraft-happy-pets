package cn.bucheng.happypets;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.bukkit.entity.EntityType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class PetMotionTest {
    @Test void diagonalMovementDoesNotOutrunForwardMovement() {
        Vector diagonal = PetMotion.steering(0, true, false, true, false, .5);
        assertEquals(.5, diagonal.length(), 1e-9);
        assertEquals(0, diagonal.getY());
    }

    @Test void oppositeKeysCancelAndYawSteersForward() {
        assertEquals(0, PetMotion.steering(30, true, true, true, true, 1).lengthSquared());
        Vector west = PetMotion.steering(90, true, false, false, false, .5);
        assertEquals(-.5, west.getX(), 1e-9);
        assertEquals(0, west.getZ(), 1e-9);
    }

    @Test void evenExtremeUpgradeLevelsRespectTheConfiguredSpeedCap() {
        PetSettings settings = SettingsFixtures.settings();
        PetData pet = new PetData(UUID.randomUUID(), UUID.randomUUID(), EntityType.COW, "Moo");
        assertEquals(settings.groundSpeed(), PetMotion.speed(pet, settings, false, false));
        pet.setSpeed(1000);
        assertEquals(settings.maxRideSpeed(), PetMotion.speed(pet, settings, false, false));
        assertEquals(settings.maxRideSpeed(), PetMotion.speed(pet, settings, true, false));
    }
}
