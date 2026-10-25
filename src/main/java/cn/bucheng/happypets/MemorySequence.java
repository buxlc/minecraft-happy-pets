package cn.bucheng.happypets;

import java.util.concurrent.ThreadLocalRandom;

/** Ordered tile sequence for the memory game, independent of Bukkit inventory events. */
final class MemorySequence {
    private final int[] tiles;
    private int cursor;

    MemorySequence(int[] tiles) {
        if (tiles.length == 0) throw new IllegalArgumentException("A memory sequence needs at least one tile");
        this.tiles = tiles.clone();
        for (int tile : this.tiles) if (tile < 0 || tile >= 9) throw new IllegalArgumentException("Tile index must be 0–8");
    }

    static MemorySequence random() {
        int[] tiles = new int[4];
        for (int index = 0; index < tiles.length; index++) tiles[index] = ThreadLocalRandom.current().nextInt(9);
        return new MemorySequence(tiles);
    }

    int size() { return tiles.length; }
    int tile(int index) { return tiles[index]; }
    boolean complete() { return cursor == tiles.length; }

    /** Advances only when the selected tile is the next expected one. */
    boolean press(int tile) {
        if (complete() || tiles[cursor] != tile) return false;
        cursor++;
        return true;
    }
}
