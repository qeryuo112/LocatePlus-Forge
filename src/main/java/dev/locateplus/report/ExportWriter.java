/*
 * Locate Plus
 * Copyright (C) 2026 forest_mask
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.locateplus.report;

import dev.locateplus.core.LPConfig;
import dev.locateplus.core.LPConstants;
import dev.locateplus.core.LPLog;
import dev.locateplus.model.BlockTally;
import dev.locateplus.model.EntityRecord;
import dev.locateplus.model.ScanResult;
import dev.locateplus.platform.Services;
import dev.locateplus.util.LongVec;
import net.minecraft.core.BlockPos;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Writes the {@code .txt} export. */
public final class ExportWriter {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter HUMAN_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ExportWriter() {
    }

    /**
     *
     * @return the written report path.
     */
    public static Path[] write(ScanResult result) throws IOException {
        Path dir = Services.platform().exportDir();
        Files.createDirectories(dir);

        String kind = result.kind() == ScanResult.Kind.BLOCKS ? "blocks" : "entities";
        String stamp = LocalDateTime.now().format(FILE_STAMP);
        String base = kind + "_" + stamp;

        Path txt = uniquePath(dir, base, ".txt");
        writeText(result, txt);
        return new Path[]{txt};
    }

    /** The kinds of block currently being listed, for the export header. */
    private static String listedKinds() {
        LPConfig config = LPConfig.get();
        List<String> on = new java.util.ArrayList<>(4);
        if (config.exportModdedBlocks()) {
            on.add("modded");
        }
        if (config.exportPlacedBlocks()) {
            on.add("placed");
        }
        if (config.exportNotableBlocks()) {
            on.add("ores and rare finds");
        }
        if (config.exportNaturalBlocks()) {
            on.add("natural terrain");
        }
        return on.isEmpty() ? "nothing" : String.join(", ", on);
    }

    private static Path uniquePath(Path dir, String base, String extension) {
        Path candidate = dir.resolve(base + extension);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(base + "_" + suffix++ + extension);
        }
        return candidate;
    }

    // ---- text ---------------------------------------------------------------------------------

    private static void writeText(ScanResult result, Path path) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            String kind = result.kind() == ScanResult.Kind.BLOCKS ? "block" : "entity";
            out.write(LPConstants.MOD_NAME + " " + kind + " analysis, "
                    + LocalDateTime.now().format(HUMAN_STAMP));
            out.newLine();
            out.newLine();

            writeHeader(result, out);

            if (result.kind() == ScanResult.Kind.BLOCKS) {
                writeBlockBody(result, out);
            } else {
                writeEntityBody(result, out);
            }
        }
    }

    private static void writeHeader(ScanResult result, BufferedWriter out) throws IOException {
        line(out, "Dimension", result.dimensionId());
        line(out, "Centred on", Chat.coords(result.originBlock()));
        line(out, "Chunks scanned", result.requestedChunks() > 0
                ? Chat.number(result.chunksScanned()) + " of "
                        + Chat.number(result.requestedChunks()) + " requested"
                : Chat.number(result.chunksScanned()));
        if (result.kind() == ScanResult.Kind.BLOCKS) {
            line(out, "Blocks checked", Chat.number(result.positionsScanned()));
        }
        line(out, "Total found", Chat.number(result.totalMatches()));
        line(out, "Forceload", result.forceload() ? "yes" : "no");
        line(out, "Took", result.durationMillis() + " ms");
        line(out, "Built by", LPConstants.MOD_NAME + " " + Services.platform().modVersion()
                + " on Minecraft " + Services.platform().minecraftVersion()
                + " (" + Services.platform().loaderName() + ")");
        if (result.truncated()) {
            line(out, "Note", "Coordinate lists cut off at "
                    + Msg.count(LPConfig.get().maxExportPositions(), "entry", "entries")
                    + " per type.");
        }
    }

    private static void line(BufferedWriter out, String label, String value) throws IOException {
        out.write(String.format("%-16s %s", label, value));
        out.newLine();
    }

    /** A section title with a rule the width of the title, so it never overruns the content. */
    private static void title(BufferedWriter out, String text) throws IOException {
        out.newLine();
        out.write(text);
        out.newLine();
        out.write("-".repeat(text.length()));
        out.newLine();
    }

    private static void writeBlockBody(ScanResult result, BufferedWriter out) throws IOException {
        List<BlockTally> tallies = result.blocksByCount();
        long total = result.totalMatches();

        title(out, Msg.count(tallies.size(), "block type") + " found");

        for (int i = 0; i < tallies.size(); i++) {
            BlockTally tally = tallies.get(i);
            double share = total == 0 ? 0 : (double) tally.count() / total;
            String nearest = tally.nearest() == null ? ""
                    : "   nearest " + Chat.coords(tally.nearest());
            out.write(String.format(Locale.US, "%4d. %-40s %12s %6s%s",
                    i + 1, tally.id(), Chat.number(tally.count()), Chat.percent(share), nearest));
            out.newLine();
        }

        List<BlockTally> unlisted = result.unlistedTallies();
        if (!unlisted.isEmpty()) {
            long omitted = 0;
            for (BlockTally tally : unlisted) {
                omitted += tally.count();
            }
            title(out, "Counted but not listed");
            out.write("Counts above are exact. " + Msg.count(omitted, "position")
                    + (omitted == 1 ? " is" : " are") + " left out of the coordinate list below.");
            out.newLine();
            out.write("Listing " + listedKinds()
                    + ". Turn on the other export_ switches to include more.");
            out.newLine();
            out.newLine();
            for (BlockTally tally : unlisted) {
                out.write(String.format(Locale.US, "  %-40s %12s",
                        tally.id(), Chat.number(tally.count())));
                out.newLine();
            }
        }

        title(out, "Coordinates");

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (BlockTally tally : tallies) {
            if (!result.listsPositions(tally)) {
                continue; // already summarised above
            }
            LongVec positions = tally.positions();
            if (positions == null || positions.isEmpty()) {
                continue;
            }
            out.newLine();
            out.write(tally.id() + " (" + Chat.number(tally.count()) + ")");
            out.newLine();
            long[] raw = positions.rawData();
            for (int i = 0; i < positions.size(); i++) {
                cursor.set(BlockPos.getX(raw[i]),
                        BlockPos.getY(raw[i]),
                        BlockPos.getZ(raw[i]));
                out.write("  " + cursor.getX() + " " + cursor.getY() + " " + cursor.getZ());
                out.newLine();
            }
            if (tally.truncated()) {
                out.write("  ... truncated");
                out.newLine();
            }
        }
    }

    private static void writeEntityBody(ScanResult result, BufferedWriter out) throws IOException {
        List<ScanResult.TypeCount> counts = result.entitiesByCount();
        long total = result.totalMatches();

        title(out, Msg.count(counts.size(), "entity type") + " found");

        for (int i = 0; i < counts.size(); i++) {
            ScanResult.TypeCount entry = counts.get(i);
            double share = total == 0 ? 0 : (double) entry.count() / total;
            out.write(String.format(Locale.US, "%4d. %-40s %12s %6s",
                    i + 1, entry.id(), Chat.number(entry.count()), Chat.percent(share)));
            out.newLine();
        }

        title(out, "Every entity, nearest first");

        // Copy before sorting: this runs on a background thread and the server thread may still be
        // reading the same list to build the chat report.
        List<EntityRecord> entities = new java.util.ArrayList<>(result.entities());
        entities.sort(java.util.Comparator.comparingDouble(EntityRecord::distance));

        for (EntityRecord record : entities) {
            out.newLine();
            out.write(record.typeId().toString());
            out.newLine();
            line(out, "  uuid", record.uuid().toString());
            line(out, "  name", record.displayName());
            if (record.customName() != null) {
                line(out, "  custom name", record.customName());
            }
            line(out, "  position", String.format(Locale.US, "%.3f %.3f %.3f",
                    record.pos().x, record.pos().y, record.pos().z));
            line(out, "  block position", Chat.coords(record.blockPos()));
            line(out, "  glowing", record.glowing() ? "yes" : "no");
            if (record.health() != null) {
                line(out, "  health", String.format(Locale.US, "%.1f / %.1f",
                        record.health(), record.maxHealth()));
            }
            if (record.itemId() != null) {
                line(out, "  item", record.itemCount() + "x " + record.itemId());
            }
        }
    }

    /** Convenience used by the command layer's async export path. */
    public static void writeAsyncLogged(ScanResult result, java.util.function.Consumer<Path[]> onDone,
                                        java.util.function.Consumer<Throwable> onFail) {
        try {
            Path[] paths = write(result);
            onDone.accept(paths);
        } catch (Throwable t) {
            LPLog.error("Export failed", t);
            onFail.accept(t);
        }
    }
}
