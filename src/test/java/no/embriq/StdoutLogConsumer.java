package no.embriq;

import org.testcontainers.containers.output.OutputFrame;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class StdoutLogConsumer implements Consumer<OutputFrame> {

    private final String prefix;
    private boolean buildFailed = false;

    public StdoutLogConsumer(String prefix) {
        this.prefix = prefix;
    }

    public boolean buildFailed() {
        return buildFailed;
    }

    @Override
    public void accept(OutputFrame outputFrame) {
        final OutputFrame.OutputType outputType = outputFrame.getType();
        final String utf8String = outputFrame.getUtf8StringWithoutLineEnding();

        // it's not possible to get the exit code when running a command with "start()"
        Stream.of("[ERROR] [ERROR]", "[INFO] BUILD FAILURE", "[FATAL]")
              .filter(utf8String::contains)
              .findFirst()
              .ifPresent(errorIndicator -> buildFailed = true);

        switch (outputType) {
            case END:
                break;
            case STDOUT:
                System.out.println(prefix + utf8String);
                break;
            case STDERR:
                System.err.println(prefix + utf8String);
                break;
            default:
                throw new IllegalArgumentException("Unexpected outputType " + outputType);
        }
    }

    public void reset() {
        buildFailed = false;
    }
}
