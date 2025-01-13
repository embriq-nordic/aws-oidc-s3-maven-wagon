package no.embriq.helpers;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.File;

public class MavenPomReader {

    public static MavenProject readPom(String path) throws JAXBException {
        JAXBContext jaxbContext = JAXBContext.newInstance(MavenProject.class);
        Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
        return (MavenProject) unmarshaller.unmarshal(new File(path));
    }

}
