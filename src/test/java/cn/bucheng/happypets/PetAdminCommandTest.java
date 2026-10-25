package cn.bucheng.happypets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PetAdminCommandTest {
    private HappyPetsPlugin plugin;
    private PetCommand command;
    private Player admin;
    private PetService service;
    private PetMenus menus;
    private PetData pet;
    @BeforeEach void setup() {
        plugin = mock(HappyPetsPlugin.class);
        service = mock(PetService.class);
        menus = mock(PetMenus.class);
        when(plugin.pets()).thenReturn(service);
        when(plugin.menus()).thenReturn(menus);
        Messages messages = mock(Messages.class);
        when(plugin.messages()).thenReturn(messages);
        admin = mock(Player.class);
        when(admin.hasPermission("happypets.admin")).thenReturn(true);
        pet = new PetData(UUID.randomUUID(), UUID.randomUUID(), EntityType.COW, "Cow");
        when(service.find(pet.id())).thenReturn(pet);
        when(service.all()).thenReturn(List.of(pet));
        command = new PetCommand(plugin);
    }

    @Test void managementCommandsWorkWithoutGameplayPermission() {
        assertFalse(admin.hasPermission("happypets.use"));
        command.onCommand(admin, null, "pets", new String[]{"adminpets"});
        verify(menus).openAllPets(admin, 0);
        command.onCommand(admin, null, "pets", new String[]{"inspect", pet.id().toString()});
        verify(menus).openPet(admin, pet.id());
        command.onCommand(admin, null, "pets", new String[]{"edit", pet.id().toString(), "level", "30"});
        verify(service).adminSet(admin, pet.id(), "level", "30");
    }

    @Test void administratorCommandsCannotBeCalledAfterPermissionIsRevoked() {
        when(admin.hasPermission("happypets.admin")).thenReturn(false);
        command.onCommand(admin, null, "pets", new String[]{"edit", pet.id().toString(), "bond", "999"});
        verifyNoInteractions(service, menus);
    }

    @Test void normalPlayersDoNotReceiveOtherOwnersPetIdentifiersInCompletion() {
        when(admin.hasPermission("happypets.admin")).thenReturn(false);
        assertTrue(command.onTabComplete(admin, null, "pets", new String[]{"inspect", ""}).isEmpty());
        assertTrue(command.onTabComplete(admin, null, "pets", new String[]{"edit", pet.id().toString(), ""}).isEmpty());
        verify(service, never()).all();
    }

    @Test void administratorsGetPetIdsAndEditableFieldCompletion() {
        assertEquals(List.of(pet.id().toString()), command.onTabComplete(admin, null, "pets", new String[]{"inspect", ""}));
        assertTrue(command.onTabComplete(admin, null, "pets", new String[]{"edit", pet.id().toString(), "bo"}).contains("bond"));
    }
}
