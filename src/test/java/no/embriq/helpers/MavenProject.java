package no.embriq.helpers;


import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "project", namespace = "http://maven.apache.org/POM/4.0.0")
public class MavenProject {

    @XmlElement(name = "groupId", namespace = "http://maven.apache.org/POM/4.0.0")
    private String groupId;

    @XmlElement(name = "artifactId", namespace = "http://maven.apache.org/POM/4.0.0")
    private String artifactId;

    @XmlElement(name = "version", namespace = "http://maven.apache.org/POM/4.0.0")
    private String version;

    // Getters and setters
    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

}
