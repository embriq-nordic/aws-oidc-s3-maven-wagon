package no.embriq;


import org.apache.maven.wagon.AbstractWagon;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.repository.Repository;
import org.apache.maven.wagon.resource.Resource;
import org.codehaus.plexus.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;

public class S3Wagon extends AbstractWagon {

    private static final Logger logger = LoggerFactory.getLogger(S3Wagon.class);

    private final S3Client s3Client;

    public S3Wagon() {
        this.s3Client = S3Client.create();
    }

    public S3Wagon(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    @Override
    protected void openConnectionInternal() {
    }

    @Override
    protected void closeConnection() {
    }

    @Override
    public void get(String resourceName, File destination) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        String key = createS3Key(resourceName);
        String bucket = getRepository().getHost();

        Resource resource = new Resource(resourceName);

        try {
            fireGetInitiated(resource, destination);
            fireGetStarted(resource, destination);

            ResponseBytes<GetObjectResponse> responseBytes = s3Client.getObjectAsBytes(r -> r.bucket(bucket).key(key));
            byte[] objectData = responseBytes.asByteArray();

            // You can use fireTransferProgress() for reporting progress
            fireTransferProgress(new TransferEvent(this, resource, TransferEvent.TRANSFER_COMPLETED, TransferEvent.REQUEST_GET),
                                 objectData, objectData.length);

            Files.write(destination.toPath(), objectData);

            resource.setContentLength(objectData.length);
            resource.setLastModified(responseBytes.response().lastModified().toEpochMilli());
            fireGetCompleted(resource, destination);
        } catch (NoSuchKeyException e) {
            fireTransferError(resource, e, TransferEvent.REQUEST_GET);
            throw new ResourceDoesNotExistException("Resource " + resourceName + " does not exist in the repository", e);
        } catch (AwsServiceException e) {
            fireTransferError(resource, e, TransferEvent.REQUEST_GET);
            handleAwsServiceException(e, resource);
        } catch (IOException | SdkException e) {
            fireTransferError(resource, e, TransferEvent.REQUEST_GET);
            throw new TransferFailedException("Error occurred while transferring resource " + resourceName, e);
        }
    }


    public void put(File source, String resourceName) throws TransferFailedException, AuthorizationException {
        String key = createS3Key(resourceName);
        String bucketName = repository.getHost();

        Resource resource = new Resource(resourceName);
        resource.setContentLength(source.length());
        resource.setLastModified(source.lastModified());

        try {
            firePutInitiated(resource, source);
            firePutStarted(resource, source);

            byte[] objectData = Files.readAllBytes(source.toPath());

            s3Client.putObject(r -> r.bucket(bucketName).key(key), RequestBody.fromBytes(objectData));

            firePutCompleted(resource, source);
        } catch (AwsServiceException e) {
            handleAwsServiceException(e, new Resource(resourceName));
        } catch (IOException | SdkException e) {
            fireTransferError(resource, e, TransferEvent.REQUEST_PUT);
            throw new TransferFailedException("Error occurred while transferring resource " + resourceName, e);
        }
    }


    @Override
    public boolean resourceExists(String resourceName) throws TransferFailedException, AuthorizationException {
        Repository repository = getRepository();
        String bucketName = repository.getHost();
        String key = createS3Key(resourceName);

        try {
            s3Client.headObject(r -> r.bucket(bucketName).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (AwsServiceException e) {
            handleAwsServiceException(e, new Resource(resourceName));
            return false;
        } catch (SdkException e) {
            throw new TransferFailedException("Error occurred while checking if resource " + resourceName + " exists", e);
        }
    }

    @Override
    public boolean getIfNewer(String resourceName, File destination, long timestamp) throws TransferFailedException,
                                                                                            ResourceDoesNotExistException,
                                                                                            AuthorizationException {
        Repository repository = getRepository();
        String bucketName = repository.getHost();
        String key = createS3Key(resourceName);

        Resource resource = new Resource(resourceName);

        try {
            HeadObjectResponse metadata = s3Client.headObject(r -> r.bucket(bucketName).key(key));
            Instant lastModified = metadata.lastModified();
            resource.setContentLength(metadata.contentLength());
            resource.setLastModified(lastModified.toEpochMilli());

            if (lastModified.toEpochMilli() > timestamp) {
                logger.info("Resource is newer, downloading: {}", resourceName);
                get(resourceName, destination);
                fireGetCompleted(resource, destination);
                return true;
            } else {
                logger.info("Resource is not newer: {}", resourceName);
                fireGetCompleted(resource, destination);
                return false;
            }
        } catch (NoSuchKeyException e) {
            fireTransferError(resource, e, TransferEvent.REQUEST_GET);
            throw new ResourceDoesNotExistException("Resource " + resourceName + " does not exist in the repository", e);
        } catch (AwsServiceException e) {
            fireTransferError(resource, e, TransferEvent.REQUEST_GET);
            handleAwsServiceException(e, resource);
            return false;
        } catch (SdkException e) {
            fireTransferError(resource, e, TransferEvent.REQUEST_GET);
            throw new TransferFailedException("Error occurred while transferring resource " + resourceName, e);
        }
    }

    private String createS3Key(String resourceName) {
        String basePath = getRepository().getBasedir();
        String s3Key = resourceName.replace('\\', '/');

        if (StringUtils.isNotEmpty(basePath)) {
            basePath = basePath.replace('\\', '/');

            if (!basePath.endsWith("/")) {
                basePath = basePath + "/";
            }

            s3Key = basePath + s3Key;
        }

        if (s3Key.startsWith("/")) {
            s3Key = s3Key.substring(1);
        }

        return s3Key;
    }

    private void handleAwsServiceException(AwsServiceException e, Resource resource) throws AuthorizationException,
                                                                                            TransferFailedException {
        String errorCode = e.awsErrorDetails().errorCode();

        if ("AccessDenied".equals(errorCode) || "InvalidAccessKeyId".equals(errorCode) || "SignatureDoesNotMatch".equals(errorCode)) {
            throw new AuthorizationException("Authorization failed: " + e.getMessage(), e);
        } else {
            throw new TransferFailedException("Error occurred while transferring resource " + resource.getName(), e);
        }
    }

    /* there is a bug in the testing framework that expects the event to not really reflects how the file looks.
     * This forces us to botch the default method
     *
    @Override
    protected void firePutInitiated( Resource resource, File localFile ) {
        TransferEvent transferEvent =
                new TransferEvent( this,
                                   new Resource(resource.getName()),
                                   TransferEvent.TRANSFER_INITIATED,
                                   TransferEvent.REQUEST_PUT );
        transferEventSupport.fireTransferInitiated(transferEvent);
    }*/
}
