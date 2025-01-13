package no.embriq;

import no.embriq.helpers.MavenPomReader;
import no.embriq.helpers.MavenProject;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.shaded.org.apache.commons.io.FileUtils;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.internal.http.loader.DefaultSdkHttpClientBuilder;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.utils.AttributeMap;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

public class Containers {

    public static final String ACCESS_KEY = "access_key";
    public static final String SECRET_KEY = "secret_key";
    public static final String REGION = "us-east-1";
    public static final String BUCKET = "bucket";

    private static Network network = Network.newNetwork();

    public static class MavenContainer implements AutoCloseable {

        private final GenericContainer<?> mavenContainer;
        private final MavenProject wagonMavenProject;
        private final StdoutLogConsumer logConsumer;

        public MavenContainer() throws Exception {
            wagonMavenProject = MavenPomReader.readPom("pom.xml");
            String artifactCachePath = wagonMavenProject.getGroupId().replaceAll("\\.", "/") + "/"
                    + wagonMavenProject.getArtifactId() + "/"
                    + wagonMavenProject.getVersion() + "/"
                    + wagonMavenProject.getArtifactId() + "-" + wagonMavenProject.getVersion();

            File wagonJar = new File("target/wagon-plugin-integration-tests.jar");
            File wagonPom = new File("pom.xml");

            File dockerBuildDir = new File("target/docker-build-area");
            FileUtils.deleteQuietly(dockerBuildDir);
            FileUtils.copyDirectory(new File("src/test/resources/integration-test"), dockerBuildDir);
            String m2CacheDockerBuild = "m2-cache";
            FileUtils.copyFile(wagonJar, new File(dockerBuildDir, m2CacheDockerBuild + "/" + artifactCachePath + ".jar"));
            FileUtils.copyFile(wagonPom, new File(dockerBuildDir, m2CacheDockerBuild + "/" + artifactCachePath + ".pom"));

            Path userHome = Paths.get(System.getProperty("user.home"));
            int uid = (Integer) Files.getAttribute(userHome, "unix:uid");
            logConsumer = new StdoutLogConsumer("+++maven-test-executor: ");
            ImageFromDockerfile imageFromDockerfile = new ImageFromDockerfile()
                    .withFileFromFile("", dockerBuildDir)
                    .withBuildArg("HOST_UID", Integer.toString(uid));


            mavenContainer = new GenericContainer<>(imageFromDockerfile)
                    .withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
                    .withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY)
                    .withEnv("AWS_REGION", REGION)
                    .withNetwork(network)
                    .withLogConsumer(logConsumer);
        }

        public void execute(String projectDirectory, String command) {
            List<String> commandParts = Arrays.stream(command.split(" ")).collect(Collectors.toList());
            commandParts.add(1, "-Dwagon.groupId=" + wagonMavenProject.getGroupId());
            commandParts.add(1, "-Dwagon.artifactId=" + wagonMavenProject.getArtifactId());
            commandParts.add(1, "-Dwagon.version=" + wagonMavenProject.getVersion());
            commandParts.add(1, "-Drepo.url=s3://" + BUCKET);
            commandParts.add(1, "-f=" + projectDirectory + "/pom.xml");
       //    mavenContainer.withCommand( "ls -la " + containerPath + "*");//commandParts.toArray(new String[0]));
            mavenContainer.withCommand( commandParts.toArray(new String[0]));
            mavenContainer.start();
            await().atMost(Duration.ofMinutes(5)).until(() -> !mavenContainer.isRunning());

            if (logConsumer.buildFailed()) {
                throw new RuntimeException("mvn build failed");
            }
            logConsumer.reset();
        }

        @Override
        public void close() {
            mavenContainer.close();
        }
    }

    public static class MotoContainer implements AutoCloseable {

        public static final int PORT = 443;

        private final GenericContainer<?> moto = new GenericContainer<>(
                DockerImageName.parse("motoserver/moto"))
                .withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
                .withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY)
                .withEnv("AWS_DEFAULT_REGION", REGION)
                .withEnv("MOTO_PORT", Integer.toString(PORT))
                .withNetwork(network)
                .withNetworkAliases(BUCKET + ".s3." + REGION + ".amazonaws.com")
                .withCommand("--ssl")
                .withExposedPorts(PORT);

        private final S3Client s3Client;

        public MotoContainer() {
            moto.start();

            StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));

            AttributeMap attributeMap = AttributeMap.builder()
                                                    .put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, true)
                                                    .build();
            SdkHttpClient sdkHttpClient = new DefaultSdkHttpClientBuilder().buildWithDefaults(attributeMap);

            s3Client = S3Client.builder()
                               .credentialsProvider(credentialsProvider)
                               .httpClient(sdkHttpClient)
                               .region(Region.of(REGION))
                               .endpointOverride(URI.create("https://localhost:" + moto.getMappedPort(PORT)))
                               .build();

            s3Client.createBucket(r -> r.bucket(BUCKET));
        }

        public S3Client getS3Client() {
            return s3Client;
        }

        @Override
        public void close() {
            s3Client.close();
            moto.stop();
        }
    }
}
