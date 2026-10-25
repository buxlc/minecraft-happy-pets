package cn.bucheng.happypets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Pig;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class PetEntityStateTest {
    @Test void controlledPetsKeepPhysicsButDoNotRunTheirOwnBehavior() {
        Mob mob = mock(Mob.class);
        PetEntityState.prepareControlledMovement(mob);
        verify(mob).setAI(true);
        verify(mob).setAware(false);
        verify(mob).setTarget(null);
    }

    @Test void bodyArmorIsIncludedInThePortableInventorySnapshot() {
        Mob mob = mock(Mob.class);
        EntityEquipment equipment = mock(EntityEquipment.class);
        ItemStack body = mock(ItemStack.class);
        when(mob.getEquipment()).thenReturn(equipment);
        when(equipment.getArmorContents()).thenReturn(new ItemStack[4]);
        when(equipment.getItem(EquipmentSlot.BODY)).thenReturn(body);
        when(body.serialize()).thenReturn(Map.of("v", 4189, "type", "WOLF_ARMOR"));
        PetData data = new PetData(UUID.randomUUID(), UUID.randomUUID(), EntityType.WOLF, "Wolf");
        PetEntityState.rememberInventory(mob, data);
        String yaml = data.appearance().get("items");
        assertTrue(yaml.contains("body:"));
        assertTrue(yaml.contains("WOLF_ARMOR"));
        verify(equipment).getItem(EquipmentSlot.BODY);
    }

    @Test void aPigsSaddleSurvivesRestoringAStoredPet() throws Exception {
        Pig pig = mock(Pig.class);
        PetData data = new PetData(UUID.randomUUID(), UUID.randomUUID(), EntityType.PIG, "Pig");
        data.appearance().put("saddle", "true");
        PetEntityState.restore(pig, data);
        verify(pig).setSaddle(true);
    }
}
