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

    @BeforeGroups("deploy_depend")
    public void setUp() {
        motoContainer = new MotoContainer();
    }

    @AfterGroups("deploy_depend")
    public void tearDown() {
        motoContainer.close();
    }

    @Test(groups = "deploy_depend")
    public void testDeploy() throws Exception {
        S3Client s3Client = motoContainer.getS3Client();
        MavenContainer mavenContainer = new MavenContainer("test-projects/deploy", "mvn deploy");

        mavenContainer.execute();

        List<String> inS3Deploy = s3Client.listObjects(r -> r.bucket(Containers.BUCKET).prefix("no/embriq"))
                                          .contents().stream().map(S3Object::key).collect(Collectors.toList());

        String artifactBasePath = "no/embriq/wagon-test/wagon-project-deploy/1.0-SNAPSHOT/wagon-project-deploy-1.0-";
        assertThat(inS3Deploy).filteredOn(obj ->obj.startsWith(artifactBasePath) && obj.endsWith(".jar"))
                              .hasSize(1);
        assertThat(inS3Deploy).filteredOn(obj ->obj.startsWith(artifactBasePath) && obj.endsWith(".pom"))
                              .hasSize(1);
    }

    @Test(groups = "deploy_depend", dependsOnMethods = "testDeploy")
    public void testResolveArtifact() throws Exception {
        MavenContainer mavenContainer = new MavenContainer("test-projects/resolve","mvn compile");
        mavenContainer.execute();
    }

}
