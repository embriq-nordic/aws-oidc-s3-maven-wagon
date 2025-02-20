# Introduction

A [Maven Wagon](https://maven.apache.org/wagon/) extension that uses the second version of the AWS SDK to pull from S3. 
That means it supports [OIDC](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_providers_create_oidc.html) which is useful 
when you set up a trust for your build system push/pull artifacts from S3.

## Why yet another S3 Wagon? 

It supports OIDC authentication, which the other ones don't.

# How to use

Either add it in the (root) POM

```xml
<build>
    <extensions>
        <extension>
            <groupId>io.github.embriq-nordic</groupId>
            <artifactId>aws-oidc-s3-maven-wagon</artifactId>
            <version>1.2.0</version>
        </extension>
    </extensions>
</build>
```

**OR** in the `.mvn/extensions.xml` file

```xml
<?xml version="1.0"?>
<extensions>
    <extension>
        <groupId>io.github.embriq-nordic</groupId>
        <artifactId>aws-oidc-s3-maven-wagon</artifactId>
        <version>1.2.0</version>
    </extension>
</extensions>
```

Next, set up your repositories to use the s3 protocol, as shown below:

```xml
<repositories>
    <repository>
        <id>my-s3-repo</id>
        <url>s3://my-s3-bucket/path</url>
    </repository>
</repositories>
```

# Java version support
From Java 8 and up _from_ version 1.2.0. In version 1.1.0 and earlier Java 11, or newer, is required.

# How it works

For pushing and pulling artifacts from S3 it's pretty standard. It uses the AWS S3 SDK to do so. The magic sauce is the 
authentication. 

If it finds the environment variables `AWS_WEB_IDENTITY_TOKEN_FILE` and `ROLE_ARN` it will try to use OIDC auth (via STS)
Otherwise it will fall back on other methods. This makes it likely to work in a wide range of environments without 
any special configuration.
[Here](https://github.com/aws/aws-sdk-java-v2/blob/master/core/auth/src/main/java/software/amazon/awssdk/auth/credentials/internal/ProfileCredentialsUtils.java#L110) is how it works

# Short-comings 

The OIDC part - the reason this plugin exists - isn't really tested. 

* [Moto](https://github.com/getmoto/moto) (or [Localstack](https://github.com/localstack/localstack) for that matter) does not really 
  support assuming roles as a pre-requisite to use AWS services (like S3 in this case). 