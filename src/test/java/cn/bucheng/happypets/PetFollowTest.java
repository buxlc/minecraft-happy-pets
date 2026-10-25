package cn.bucheng.happypets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class PetFollowTest {
    private HappyPetsPlugin plugin;
    private PetService service;
    private PetData pet;
    private Mob mob;
    private Player owner;
    private World world;
    private Location position;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach void setup() throws Exception {
        plugin = mock(HappyPetsPlugin.class);
        when(plugin.getName()).thenReturn("HappyPets");
        PetSettings settings = SettingsFixtures.settings();
        when(plugin.settings()).thenReturn(settings);
        Messages messages = mock(Messages.class);
        when(plugin.messages()).thenReturn(messages);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        WorldBorder border = mock(WorldBorder.class);
        when(world.getWorldBorder()).thenReturn(border);
        when(border.isInside(any(Location.class))).thenReturn(true);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        owner = mock(Player.class);
        UUID ownerId = UUID.randomUUID();
        when(owner.getUniqueId()).thenReturn(ownerId);
        when(owner.getWorld()).thenReturn(world);
        when(owner.hasPermission("happypets.use")).thenReturn(true);
        when(owner.getLocation()).thenAnswer(invocation -> new Location(world, 0, 65, 0));
        mob = mock(Mob.class);
        when(mob.isValid()).thenReturn(true);
        when(mob.getWorld()).thenReturn(world);
        position = new Location(world, 10, 65, 0);
        when(mob.getLocation()).thenAnswer(invocation -> position.clone());
        when(mob.getVelocity()).thenAnswer(invocation -> new Vector(0, -.08, 0));
        pet = new PetData(UUID.randomUUID(), ownerId, EntityType.COW, "Moo");
        service = serviceWith(pet);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(owner);
    }

    @AfterEach void cleanup() { if (bukkit != null) bukkit.close(); }

    @Test void followMovesTowardTheOwnerAndPreservesVerticalPhysics() throws Exception {
        follow();
        Vector sent = movement();
        assertTrue(sent.getX() < 0, "Pet must move toward the owner at x=0");
        assertEquals(0, sent.getZ(), 1e-9);
        assertEquals(-.08, sent.getY(), 1e-9);
        assertTrue(sent.length() <= plugin.settings().maxRideSpeed());
    }

    @Test void switchingFromStayToFollowRestoresMovementOnAnExistingPet() throws Exception {
        pet.setMode(PetData.Mode.STAY);
        follow();
        assertEquals(0, movement().getX(), 1e-9);
        clearInvocations(mob);

        assertTrue(service.setMode(owner, pet.id(), PetData.Mode.FOLLOW));
        verify(mob).setAI(true);
        verify(mob).setAware(false);
        verify(mob).setTarget(null);
        clearInvocations(mob);
        follow();
        assertTrue(movement().getX() < 0);
        assertEquals(PetData.Mode.FOLLOW, pet.mode());
    }

    @Test void stayModeDoesNotIssueHorizontalMovement() throws Exception {
        pet.setMode(PetData.Mode.STAY);
        follow();
        Vector sent = movement();
        assertEquals(0, sent.getX());
        assertEquals(0, sent.getZ());
        assertEquals(-.08, sent.getY(), 1e-9);
    }

    @Test void petsStopWithinTheConfiguredFollowingDistance() throws Exception {
        position.setX(2);
        follow();
        assertEquals(0, movement().getX());
        assertEquals(0, movement().getZ());
    }

    @Test void followingDoesNotForceAnUnloadedDestinationChunkToLoad() throws Exception {
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        follow();
        assertEquals(0, movement().lengthSquared());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
        verify(world, never()).loadChunk(anyInt(), anyInt());
    }

    @Test void flyingPetsCanFollowAnOwnerAboveThem() throws Exception {
        pet = new PetData(UUID.randomUUID(), owner.getUniqueId(), EntityType.PARROT, "Bird");
        service = serviceWith(pet);
        position.setY(60);
        follow();
        Vector sent = movement();
        assertTrue(sent.getX() < 0);
        assertTrue(sent.getY() > 0);
    }

    private Vector movement() {
        ArgumentCaptor<Vector> velocity = ArgumentCaptor.forClass(Vector.class);
        verify(mob).setVelocity(velocity.capture());
        return velocity.getValue();
    }

    @SuppressWarnings("unchecked") private PetService serviceWith(PetData data) throws Exception {
        PetService created = new PetService(plugin, mock(PetStore.class), List.of(data));
        Field field = PetService.class.getDeclaredField("entities");
        field.setAccessible(true);
        ((Map<UUID, Mob>) field.get(created)).put(data.id(), mob);
        return created;
    }

    private void follow() throws Exception {
        Method method = PetService.class.getDeclaredMethod("follow", UUID.class);
        method.setAccessible(true);
        method.invoke(service, pet.id());
    }
}
