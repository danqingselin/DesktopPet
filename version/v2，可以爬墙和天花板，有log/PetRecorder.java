import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class PetRecorder implements AutoCloseable {
    private final Path dir;
    private final Path file;
    private final PrintWriter out;
    private final long startNanos = System.nanoTime();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public PetRecorder(Path dir) {
        try {
            this.dir = dir;
            Files.createDirectories(dir);
            String ts = LocalDateTime.now().format(TS);
            this.file = dir.resolve("pet-" + ts + ".csv");
            this.out = new PrintWriter(Files.newBufferedWriter(
                    file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
            out.println("time,uptime_ms,tick,action,state,x,y,detail");
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException("无法创建日志文件", e);
        }
    }

    public synchronized void log(int tick, String action, String state, int x, int y, String detail) {
        String now = LocalDateTime.now().format(ISO);
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        if (detail == null) detail = "";
        // 防止逗号把CSV列打乱
        detail = detail.replace('\n', ' ').replace(',', '；');
        out.printf("%s,%d,%d,%s,%s,%d,%d,%s%n", now, ms, tick, action, state, x, y, detail);
        out.flush();
    }

    public Path getFile() { return file; }

    @Override
    public void close() { out.close(); }
}
