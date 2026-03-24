package com.example.AutomationJavaUtility.Configuration;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Component
public class S3ClientFactory {

    @Value("${storage.provider:s3}")
    private String provider;

    @Value("${aws.region:ap-south-1}")
    private String awsRegion;

    @Value("${aws.accessKeyId:}")
    private String awsAccessKey;

    @Value("${aws.secretAccessKey:}")
    private String awsSecretKey;

    @Value("${s3.bucket.name:}")
    private String awsBucket;

    @Value("${minio.endpoint:}")
    private String minioEndpoint;

    @Value("${minio.accessKey:}")
    private String minioAccessKey;

    @Value("${minio.secretKey:}")
    private String minioSecretKey;

    @Value("${minio.bucket:}")
    private String minioBucket;

    private S3Client cachedClient;
    private String cachedBucket;

    public synchronized S3Client getClient() {

        if (cachedClient != null) {
            return cachedClient;
        }

        if ("minio".equalsIgnoreCase(provider)) {

            cachedClient = S3Client.builder()
                    .endpointOverride(URI.create(minioEndpoint))
                    .region(Region.US_EAST_1)
                    .credentialsProvider(
                            StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create(
                                            minioAccessKey, minioSecretKey)))
                    .serviceConfiguration(
                            S3Configuration.builder()
                                    .pathStyleAccessEnabled(true)
                                    .build())
                    .build();

            cachedBucket = minioBucket;
            return cachedClient;
        }

        cachedClient = S3Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(
                                        awsAccessKey, awsSecretKey)))
                .build();

        cachedBucket = awsBucket;
        return cachedClient;
    }

    public String resolveBucket() {
        if (cachedBucket == null) {
            getClient();
        }
        return cachedBucket;
    }
}