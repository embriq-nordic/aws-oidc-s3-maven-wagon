package no.embriq;

import no.embriq.Containers.MavenContainer;
import no.embriq.Containers.MotoContainer;

import org.testng.annotations.AfterGroups;
import org.testng.annotations.BeforeGroups;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class S3WagonIntegrationTest {

    private MotoContainer motoContainer;
    private MavenContainer mavenContainer;

    @BeforeGroups("deploy_depend")
    public void setUp() throws Exception {
        motoContainer = new MotoContainer();
        mavenContainer = new MavenContainer();
    }

    @AfterGroups("deploy_depend")
    public void tearDown() {
        motoContainer.close();
        mavenContainer.close(); // it should already have died, really
    }

    @Test(groups = "deploy_depend")
    public void testDeploy() {
        S3Client s3Client = motoContainer.getS3Client();

        mavenContainer.execute("test-projects/deploy", "mvn deploy");

        List<String> inS3Deploy = s3Client.listObjects(r -> r.bucket(Containers.BUCKET).prefix("no/embriq"))
                                          .contents().stream().map(S3Object::key).collect(Collectors.toList());

        String artifactBasePath = "no/embriq/wagon-test/wagon-project-deploy/1.0-SNAPSHOT/wagon-project-deploy-1.0-";
        assertThat(inS3Deploy).filteredOn(obj ->obj.startsWith(artifactBasePath) && obj.endsWith(".jar"))
                              .hasSize(1);
        assertThat(inS3Deploy).filteredOn(obj ->obj.startsWith(artifactBasePath) && obj.endsWith(".pom"))
                              .hasSize(1);
    }

    @Test(groups = "deploy_depend", dependsOnMethods = "testDeploy")
    public void testResolveArtifact() {
        mavenContainer.execute("test-projects/resolve","mvn compile");
    }

}
