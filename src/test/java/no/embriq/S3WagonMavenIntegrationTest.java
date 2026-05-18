package no.embriq;

import no.embriq.helpers.MavenPomReader;
import no.embriq.helpers.MavenProject;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.StringBuilderWriter;
import org.apache.commons.io.output.TeeWriter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class S3WagonMavenIntegrationTest {

    private static final String ACCESS_KEY = "access_key";
    private static final String SECRET_KEY = "secret_key";
    private static final String REGION = "us-east-1";
    private static final String BUCKET = "bucket";
    private static final int PORT = 5000;

    private GenericContainer<?> moto;
    private S3Client s3Client;

    @BeforeClass
    public void setUp() {
        moto = new GenericContainer<>(DockerImageName.parse("motoserver/moto"))
                .withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
                .withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY)
                .withEnv("AWS_DEFAULT_REGION", REGION)
                .withExposedPorts(PORT);
        moto.start();

        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));
        s3Client = S3Client.builder()
                           .credentialsProvider(credentialsProvider)
                           .region(Region.of(REGION))
                           .endpointOverride(URI.create(s3Endpoint()))
                           .build();

        s3Client.createBucket(r -> r.bucket(BUCKET));
    }

    @AfterClass
    public void tearDown() {
        if (moto != null) {
            moto.stop();
        }
    }

    @Test
    public void mavenDeployUsesS3WagonExtension() throws Exception {
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        MavenProject wagonProject = MavenPomReader.readPom(projectRoot.resolve("pom.xml").toString());
        Path localRepository = Files.createTempDirectory(getClass().getSimpleName() + "-m2");
        installWagonInLocalRepository(projectRoot, localRepository, wagonProject);

        Path consumerProject = Files.createTempDirectory(getClass().getSimpleName());
        Files.write(consumerProject.resolve("pom.xml"), consumerPom(wagonProject).getBytes(StandardCharsets.UTF_8));

        Map<String, String> environment = new HashMap<>();
        environment.put("AWS_ACCESS_KEY_ID", ACCESS_KEY);
        environment.put("AWS_SECRET_ACCESS_KEY", SECRET_KEY);
        environment.put("AWS_REGION", REGION);
        environment.put("AWS_DEFAULT_REGION", REGION);

        CommandResult result = run(
                consumerProject,
                environment,
                projectRoot.resolve("mvnw").toString(),
                "-Dmaven.repo.local=" + localRepository,
                "-Ds3.wagon.endpoint=" + s3Endpoint(),
                "deploy"
        );

        assertThat(result.exitCode).as(result.output).isEqualTo(0);
        s3Client.headObject(r -> r.bucket(BUCKET).key("releases/com/example/wagon-consumer/1.0.0/wagon-consumer-1.0.0.pom"));
    }

    private void installWagonInLocalRepository(Path projectRoot, Path localRepository, MavenProject wagonProject) throws IOException, InterruptedException {
        Path wagonJar = projectRoot.resolve("target/wagon-plugin.jar");
        assertThat(wagonJar).exists();

        CommandResult result = run(
                projectRoot,
                Collections.emptyMap(),
                projectRoot.resolve("mvnw").toString(),
                "-Dmaven.repo.local=" + localRepository,
                "org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file",
                "-Dfile=" + wagonJar,
                "-DpomFile=" + projectRoot.resolve("pom.xml")
        );

        assertThat(result.exitCode).as(result.output).isEqualTo(0);
        assertThat(localRepository.resolve(localRepositoryPath(wagonProject))).exists();
    }

    private String s3Endpoint() {
        return "http://localhost:" + moto.getMappedPort(PORT);
    }

    private static String localRepositoryPath(MavenProject project) {
        return project.getGroupId().replace('.', '/')
                + "/" + project.getArtifactId()
                + "/" + project.getVersion()
                + "/" + project.getArtifactId() + "-" + project.getVersion() + ".jar";
    }

    private static String consumerPom(MavenProject wagonProject) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
                + "    <modelVersion>4.0.0</modelVersion>\n"
                + "    <groupId>com.example</groupId>\n"
                + "    <artifactId>wagon-consumer</artifactId>\n"
                + "    <version>1.0.0</version>\n"
                + "    <packaging>pom</packaging>\n"
                + "    <build>\n"
                + "        <extensions>\n"
                + "            <extension>\n"
                + "                <groupId>" + wagonProject.getGroupId() + "</groupId>\n"
                + "                <artifactId>" + wagonProject.getArtifactId() + "</artifactId>\n"
                + "                <version>" + wagonProject.getVersion() + "</version>\n"
                + "            </extension>\n"
                + "        </extensions>\n"
                + "        <plugins>\n"
                + "            <plugin>\n"
                + "                <groupId>org.apache.maven.plugins</groupId>\n"
                + "                <artifactId>maven-deploy-plugin</artifactId>\n"
                + "                <version>3.1.2</version>\n"
                + "            </plugin>\n"
                + "        </plugins>\n"
                + "    </build>\n"
                + "    <distributionManagement>\n"
                + "        <repository>\n"
                + "            <id>s3-it</id>\n"
                + "            <url>s3://bucket/releases</url>\n"
                + "        </repository>\n"
                + "    </distributionManagement>\n"
                + "</project>\n";
    }

    private static CommandResult run(Path directory, Map<String, String> environment, String... command)
            throws IOException, InterruptedException {
        ArrayList<String> arguments = new ArrayList<>();
        Collections.addAll(arguments, command);

        ProcessBuilder processBuilder = new ProcessBuilder(arguments)
                .directory(directory.toFile())
                .redirectErrorStream(true);
        processBuilder.environment().putAll(environment);

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();
        Thread outputReader = new Thread(() -> readOutput(process, output));
        outputReader.start();

        boolean finished = process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
        outputReader.join(TimeUnit.SECONDS.toMillis(5));

        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("Command timed out: " + String.join(" ", arguments) + "\n" + output);
        }

        return new CommandResult(process.exitValue(), output.toString());
    }

    private static void readOutput(Process process, StringBuilder output) {
        try (Writer writer = new TeeWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8),
                new StringBuilderWriter(output))) {
            IOUtils.copy(process.getInputStream(), writer, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static class CommandResult {
        private final int exitCode;
        private final String output;

        private CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
