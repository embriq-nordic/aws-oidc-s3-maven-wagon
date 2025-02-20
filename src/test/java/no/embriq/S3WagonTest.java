package no.embriq;

import org.apache.maven.wagon.observers.Debug;
import org.apache.maven.wagon.repository.Repository;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.shaded.org.apache.commons.io.FileUtils;
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
 * It would have been awfully nice to a testing library for this. Unfortunatly the wagon testing harness provided by
 * the wagon people themselves is completely broken. I kinda wonder if they even tried it themselves.
 * So none of the wagon extensions I've seen actually use it.
 */
public class S3WagonTest {

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
        // Create a temporary file to push to the S3 bucket
        File sourceFile = tempDir.resolve("test-upload.txt").toFile();
        Files.write(sourceFile.toPath(), "This is a test file".getBytes(StandardCharsets.UTF_8));

        // Push the file to the S3 bucket
        String remoteResourceName = "test-folder/test-upload.txt";
        wagon.put(sourceFile, remoteResourceName);

        // Create another temporary file to download the file from the S3 bucket
        File destinationFile = tempDir.resolve("test-download.txt").toFile();

        // Pull the file from the S3 bucket
        wagon.get(remoteResourceName, destinationFile);

        // Compare the contents of the source and destination files
        String sourceContent = FileUtils.readFileToString(sourceFile, StandardCharsets.UTF_8);
        String destinationContent = FileUtils.readFileToString(destinationFile, StandardCharsets.UTF_8);
        assertThat(sourceContent).isEqualTo(destinationContent);
    }

    @Test
    public void testGetIfNewer() throws Exception {
        // Create a temporary file to push to the S3 bucket
        File originalFile = tempDir.resolve("test-upload.txt").toFile();
        Files.write(originalFile.toPath(), "Content".getBytes(StandardCharsets.UTF_8));

        // Push the file to the S3 bucket
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
        // Create a temporary file to push to the S3 bucket
        File file = tempDir.resolve("test-upload.txt").toFile();
        Files.write(file.toPath(), "Content".getBytes(StandardCharsets.UTF_8));

        wagon.put(file, "dir1/dir2/dir3/file.txt");
        wagon.put(file, "dir1/dir2/file.txt");
        wagon.put(file, "dir1/file.txt");

        List<String> files = wagon.getFileList("dir1");

        assertThat(files).contains("dir2", "file.txt");
    }

}