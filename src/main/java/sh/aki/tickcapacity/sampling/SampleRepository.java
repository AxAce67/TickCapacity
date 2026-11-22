package sh.aki.tickcapacity.sampling;

import sh.aki.tickcapacity.core.model.Sample;
import sh.aki.tickcapacity.core.storage.SampleCsv;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/** One CSV file per day. Blocking; keep it off the main thread. */
public final class SampleRepository {

    private final Path dir;
    private final Logger logger;
    private final ZoneId zone = ZoneId.systemDefault();

    public SampleRepository(Path dir, Logger logger) {
        this.dir = dir;
        this.logger = logger;
    }

    public void append(Sample sample) {
        Path file = fileFor(LocalDate.ofInstant(Instant.ofEpochMilli(sample.epochMillis()), zone));
        try {
            Files.createDirectories(dir);
            boolean fresh = Files.notExists(file);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (fresh) {
                    w.write(SampleCsv.HEADER);
                    w.newLine();
                }
                w.write(SampleCsv.encode(sample));
                w.newLine();
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not write sample to " + file, e);
        }
    }

    public List<Sample> loadRecentDays(int days) {
        LocalDate from = LocalDate.now(zone).minusDays(days - 1L);
        List<Sample> out = new ArrayList<>();
        for (Path file : files()) {
            LocalDate d = dateOf(file);
            if (d != null && !d.isBefore(from)) {
                try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
                    lines.map(SampleCsv::decode).flatMap(java.util.Optional::stream).forEach(out::add);
                } catch (IOException | java.io.UncheckedIOException e) {
                    logger.log(Level.WARNING, "Could not read " + file, e);
                }
            }
        }
        out.sort(Comparator.comparingLong(Sample::epochMillis));
        return out;
    }

    public void purge(int retentionDays) {
        LocalDate cutoff = LocalDate.now(zone).minusDays(retentionDays);
        for (Path file : files()) {
            LocalDate d = dateOf(file);
            if (d != null && d.isBefore(cutoff)) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Could not delete " + file, e);
                }
            }
        }
    }

    private Path fileFor(LocalDate date) {
        return dir.resolve(date + ".csv");
    }

    private List<Path> files() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".csv")).sorted().toList();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not list " + dir, e);
            return List.of();
        }
    }

    private static LocalDate dateOf(Path file) {
        String name = file.getFileName().toString();
        try {
            return LocalDate.parse(name.substring(0, name.length() - ".csv".length()));
        } catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
            return null;
        }
    }
}
