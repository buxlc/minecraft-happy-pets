package cn.bucheng.happypets;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PetStoreTest {
    @TempDir Path folder;

    @Test void allPetStateSurvivesAWriteAndReopen() {
        PetData pet = pet();
        pet.setXp(12345);
        pet.setBond(87);
        pet.setPoints(3);
        pet.setSpeed(4);
        pet.setHealth(5);
        pet.setJump(6);
        pet.setMode(PetData.Mode.STAY);
        pet.setLastFeed(1_700_000_000_123L);
        pet.setLastPlay(1_700_000_000_456L);
        pet.setBaseHealth(12.5);
        pet.setBaseSpeed(0.175);
        pet.appearance().put("color", "BROWN");
        pet.appearance().put("variant", "RED");
        PetStore store = store();
        assertTrue(store.load().isEmpty());
        store.close(List.of(pet));
        PetStore reopened = store();
        List<PetData> pets = reopened.load();
        assertEquals(1, pets.size());
        PetData read = pets.getFirst();
        assertEquals(pet.id(), read.id());
        assertEquals(pet.owner(), read.owner());
        assertEquals(pet.name(), read.name());
        assertEquals(pet.type(), read.type());
        assertEquals(pet.xp(), read.xp());
        assertEquals(pet.bond(), read.bond());
        assertEquals(pet.points(), read.points());
        assertEquals(pet.speed(), read.speed());
        assertEquals(pet.health(), read.health());
        assertEquals(pet.jump(), read.jump());
        assertEquals(pet.mode(), read.mode());
        assertEquals(pet.lastFeed(), read.lastFeed());
        assertEquals(pet.lastPlay(), read.lastPlay());
        assertEquals(pet.baseHealth(), read.baseHealth());
        assertEquals(pet.baseSpeed(), read.baseSpeed());
        assertEquals(pet.appearance(), read.appearance());
        reopened.close(pets);
        assertTrue(Files.exists(folder.resolve("pets.yml.bak")));
    }

    @Test void corruptAndUnknownSchemaFilesAreNeverOverwritten() throws Exception {
        for (String content : List.of("pets: [broken yaml", "schema-version: 2\npets: []\n", "schema-version: 1\npets: wrong\n")) {
            Files.writeString(folder.resolve("pets.yml"), content);
            PetStore store = store();
            assertThrows(IllegalStateException.class, store::load);
            assertThrows(IllegalStateException.class, () -> store.saveAsync(List.of()));
            store.close(List.of());
            assertEquals(content, Files.readString(folder.resolve("pets.yml")));
        }
    }

    @Test void duplicateIdsAreRefusedWithoutDiscardingTheFile() throws Exception {
        PetData pet = pet();
        PetStore original = store();
        original.load();
        original.close(List.of(pet));
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(folder.resolve("pets.yml").toFile());
        List<?> current = yaml.getList("pets");
        List<Object> duplicate = new ArrayList<>(current);
        duplicate.add(current.getFirst());
        yaml.set("pets", duplicate);
        yaml.save(folder.resolve("pets.yml").toFile());
        PetStore duplicateStore = store();
        assertThrows(IllegalStateException.class, duplicateStore::load);
        duplicateStore.close(List.of());
    }

    @Test void finalSnapshotWinsOverQueuedSnapshotsAndClosedStoreRejectsSaves() {
        PetStore store = store();
        store.load();
        PetData pet = pet();
        for (int i = 0; i < 100; i++) {
            pet.setXp(i);
            store.saveAsync(List.of(pet));
        }
        pet.setXp(1000);
        store.close(List.of(pet));
        assertThrows(IllegalStateException.class, () -> store.saveAsync(List.of()));
        PetStore reopened = store();
        List<PetData> result = reopened.load();
        assertEquals(1000, result.getFirst().xp());
        reopened.close(result);
    }

    @Test void asynchronousSaveCapturesValuesBeforeTheCallerMutatesThem() throws Exception {
        PetStore store = store();
        store.load();
        PetData pet = pet();
        store.saveAsync(List.of(pet));
        pet.setName("改名之后");
        pet.appearance().put("color", "RED");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(folder.resolve("pets.yml")) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(folder.resolve("pets.yml")));
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(folder.resolve("pets.yml").toFile());
        var stored = yaml.getMapList("pets").getFirst();
        assertEquals("小伙伴", stored.get("name"));
        assertEquals(java.util.Map.of(), stored.get("appearance"));
        store.close(List.of(pet));
    }

    @Test void missingPrimaryWithAnExistingBackupRequiresExplicitRecovery() throws Exception {
        Files.writeString(folder.resolve("pets.yml.bak"), "schema-version: 1\npets: []\n");
        PetStore store = store();
        assertThrows(IllegalStateException.class, store::load);
        store.close(List.of());
        assertFalse(Files.exists(folder.resolve("pets.yml")));
    }

    @Test void portableInventoryYamlAndAnEmptyInventoryStringRemainLoadable() {
        PetData equipped = pet();
        PetData empty = pet();
        equipped.appearance().put("items", "inventory:\n" + "  - serialized-item-data\n".repeat(100));
        empty.appearance().put("items", "");
        PetStore store = store();
        store.load();
        store.close(List.of(equipped, empty));
        PetStore reopened = store();
        List<PetData> pets = reopened.load();
        assertEquals(equipped.appearance().get("items"), pets.get(0).appearance().get("items"));
        assertEquals("", pets.get(1).appearance().get("items"));
        reopened.close(pets);
    }

    private PetStore store() { return new PetStore(folder, Logger.getLogger("HappyPetsTest")); }
    private PetData pet() { return new PetData(UUID.randomUUID(), UUID.randomUUID(), EntityType.SHEEP, "小伙伴"); }
}
