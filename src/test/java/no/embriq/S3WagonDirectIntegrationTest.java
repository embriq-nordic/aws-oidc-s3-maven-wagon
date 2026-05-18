package no.embriq;

import org.apache.commons.io.FileUtils;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferListener;
import org.apache.maven.wagon.observers.Debug;
import org.apache.maven.wagon.repository.Repository;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * It would have been awfully nice to have a testing library for this. Unfortunately the wagon testing harness provided by
 * the wagon people themselves is completely broken. I kinda wonder if they even tried it themselves.
 * So none of the wagon extensions I've seen actually use it.
 */
public class S3WagonDirectIntegrationTest {

    public static final String ACCESS_KEY = "access_key";
    public static final String SECRET_KEY = "secret_key";
    public static final int PORT = 5000;
    public static final String REGION = "us-east-1";
    public static final String BUCKET = "bucket";

    private GenericContainer<?> moto;
    private S3Wagon wagon;
    private S3Client s3Client;
    private Path tempDir;

    @BeforeTest
    public void setUp() throws Exception {
        moto = new GenericContainer<>(
                DockerImageName.parse("motoserver/moto"))
                .withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
                .withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY)
                .withEnv("AWS_DEFAULT_REGION", REGION)
                .withExposedPorts(PORT);
        moto.start();

        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));
        s3Client = S3Client.builder()
                           .credentialsProvider(credentialsProvider)
                           .region(Region.of(REGION))
                           .endpointOverride(URI.create("http://localhost:" + moto.getMappedPort(PORT)))
                           .build();

        s3Client.createBucket(r -> r.bucket(BUCKET));

        wagon = new S3Wagon(s3Client);
        Debug debug = new Debug();
        wagon.addSessionListener(debug);
        wagon.addTransferListener(debug);
        wagon.connect(new Repository("nisse", "s3://" + BUCKET));

        tempDir = Files.createTempDirectory(getClass().getSimpleName());
        FileUtils.forceDeleteOnExit(tempDir.toFile());
    }

    @AfterTest
    public void tearDown() {
        moto.stop();
        FileUtils.deleteQuietly(tempDir.toFile());
    }

    @Test
    public void testPushAndPullFiles() throws Exception {
        File sourceFile = tempDir.resolve("test-upload.txt").toFile();
        Files.write(sourceFile.toPath(), "This is a test file".getBytes(StandardCharsets.UTF_8));

        String remoteResourceName = "test-folder/test-upload.txt";
        wagon.put(sourceFile, remoteResourceName);

        File destinationFile = tempDir.resolve("test-download.txt").toFile();
        wagon.get(remoteResourceName, destinationFile);

        String sourceContent = FileUtils.readFileToString(sourceFile, StandardCharsets.UTF_8);
        String destinationContent = FileUtils.readFileToString(destinationFile, StandardCharsets.UTF_8);
        assertThat(sourceContent).isEqualTo(destinationContent);
    }

    @Test
    public void testGetIfNewer() throws Exception {
        File originalFile = tempDir.resolve("test-upload.txt").toFile();
        Files.write(originalFile.toPath(), "Content".getBytes(StandardCharsets.UTF_8));

        String remoteResourceName = "test-folder/test-upload.txt";
        long beforeFirstUpload = System.currentTimeMillis() - 1000;
        wagon.put(originalFile, remoteResourceName);
        Thread.sleep(1000); // moto uses epoch (seconds). dang.
        long afterFirstUpload = System.currentTimeMillis();

        File fetchedContent = tempDir.resolve("test-download.txt").toFile();
        wagon.getIfNewer(remoteResourceName, fetchedContent, afterFirstUpload);
        assertThat(fetchedContent).doesNotExist();

        wagon.getIfNewer(remoteResourceName, fetchedContent, beforeFirstUpload);
        assertThat(fetchedContent).exists();
    }

    @Test
    public void testListFiles() throws Exception {
        File file = tempDir.resolve("test-upload.txt").toFile();
        Files.write(file.toPath(), "Content".getBytes(StandardCharsets.UTF_8));

        wagon.put(file, "dir1/dir2/dir3/file.txt");
        wagon.put(file, "dir1/dir2/file.txt");
        wagon.put(file, "dir1/file.txt");

        List<String> files = wagon.getFileList("dir1");

        assertThat(files).contains("dir2", "file.txt");
    }

    @Test
    public void reportsDownloadSizeBeforeProgress() throws Exception {
        File sourceFile = tempDir.resolve("progress-test-upload.txt").toFile();
        byte[] sourceContent = "This is a test file with enough content to report progress".getBytes(StandardCharsets.UTF_8);
        Files.write(sourceFile.toPath(), sourceContent);

        String remoteResourceName = "test-folder/progress-test-upload.txt";
        wagon.put(sourceFile, remoteResourceName);

        TransferRecorder transferRecorder = new TransferRecorder();
        wagon.addTransferListener(transferRecorder);

        wagon.get(remoteResourceName, tempDir.resolve("progress-test-download.txt").toFile());

        assertThat(transferRecorder.startedContentLength).isEqualTo(sourceContent.length);
        assertThat(transferRecorder.progressEventType).isEqualTo(TransferEvent.TRANSFER_PROGRESS);
        assertThat(transferRecorder.progressBytes).isEqualTo(sourceContent.length);
        assertThat(transferRecorder.completedContentLength).isEqualTo(sourceContent.length);
    }

    private static class TransferRecorder implements TransferListener {
        private long startedContentLength;
        private long completedContentLength;
        private int progressEventType;
        private long progressBytes;

        @Override
        public void transferInitiated(TransferEvent transferEvent) {
        }

        @Override
        public void transferStarted(TransferEvent transferEvent) {
            if (transferEvent.getRequestType() == TransferEvent.REQUEST_GET) {
                startedContentLength = transferEvent.getResource().getContentLength();
            }
        }

        @Override
        public void transferProgress(TransferEvent transferEvent, byte[] buffer, int length) {
            if (transferEvent.getRequestType() == TransferEvent.REQUEST_GET) {
                progressEventType = transferEvent.getEventType();
                progressBytes += length;
            }
        }

        @Override
        public void transferCompleted(TransferEvent transferEvent) {
            if (transferEvent.getRequestType() == TransferEvent.REQUEST_GET) {
                completedContentLength = transferEvent.getResource().getContentLength();
            }
        }

        @Override
        public void transferError(TransferEvent transferEvent) {
        }

        @Override
        public void debug(String message) {
        }
    }

}
