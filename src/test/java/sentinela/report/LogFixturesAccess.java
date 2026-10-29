package sentinela.report;

import java.io.IOException;
import java.nio.file.Path;

public final class LogFixturesAccess {
    private LogFixturesAccess() {}

    public static Path readerLog(Path dir, String mode, String run) throws IOException {
        return LogFixtures.readerLog(dir, mode, run, LogFixtures.START);
    }
}
