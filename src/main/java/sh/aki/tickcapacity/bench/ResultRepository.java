package sh.aki.tickcapacity.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Blocking; keep it off the main thread. */
public final class ResultRepository {

    /** Ids are timestamps; anything else is rejected so an id can never become a path. */
    private static final Pattern ID = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{6}(-[0-9]+)?");

    static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeSpecialFloatingPointValues() // Infinity = "no limit found", NaN = unknown
            .disableHtmlEscaping()
            .create();

    private final Path dir;
    private final Logger logger;

    public ResultRepository(Path dir, Logger logger) {
        this.dir = dir;
        this.logger = logger;
    }

    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public Path save(BenchmarkResult result) throws IOException {
        if (!isValidId(result.id())) {
            throw new IllegalArgumentException("invalid id " + result.id());
        }
        Files.createDirectories(dir);
        Path target = dir.resolve(result.id() + ".json");
        Path tmp = dir.resolve(result.id() + ".json.tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(result, w);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    public Optional<BenchmarkResult> load(String id) {
        if (!isValidId(id)) {
            return Optional.empty();
        }
        Path file = dir.resolve(id + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return Optional.ofNullable(GSON.fromJson(r, BenchmarkResult.class));
        } catch (IOException | JsonParseException e) {
            logger.log(Level.WARNING, "Could not read benchmark result " + file, e);
            return Optional.empty();
        }
    }

    public List<String> listIds() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5))
                    .filter(ResultRepository::isValidId)
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not list " + dir, e);
            return List.of();
        }
    }

    /** Skips runs that were cancelled too early to give an estimate. */
    public Optional<BenchmarkResult> latestUsable() {
        return listIds().stream().limit(20).map(this::load).flatMap(Optional::stream)
                .filter(r -> r.estimate() != null && r.estimate().confidence()
                        != sh.aki.tickcapacity.core.estimate.Confidence.INSUFFICIENT)
                .findFirst();
    }
}
