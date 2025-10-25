package com.example.AutomationJavaUtility.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.springframework.http.HttpStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.example.AutomationJavaUtility.Utils.FvuJarRunner;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import jakarta.annotation.PostConstruct;
@Service
public class FvuService {

    private static final Logger logger = LoggerFactory.getLogger(FvuService.class);

    @Value("${fvu.max.concurrent:5}")
    private int maxConcurrent;

    private Semaphore semaphore;

    @PostConstruct
    public void initSemaphore() {
        this.semaphore = new Semaphore(maxConcurrent, true); // fair queueing
        logger.info("Initialized semaphore with {} max concurrent slots", maxConcurrent);
    }

	
	 private String outputDir;
	  
	  private String jarPath;
	  
	 private String s3BucketName;
	  
	 private String accessKey;
	  
	  private String secretKey; 
	  private final S3Client s3Client;
	 
    public FvuService(
            @Value("${aws.accessKeyId}") String accessKey,
            @Value("${aws.secretAccessKey}") String secretKey,
            @Value("${aws.region}") String region,
            @Value("${s3.bucket.name}") String s3BucketName,
            @Value("${fvu.output.dir}") String outputDir,
            @Value("${fvu.jar.path}") String jarPath) {

        this.s3BucketName = s3BucketName;
        this.outputDir = outputDir;
        this.jarPath = jarPath;

        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(accessKey, secretKey);
        this.s3Client = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                .build();

        logger.info("S3 client initialized successfully for region {}", region);
    }
    
    private String generateUniqueFolderName(String baseName) {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now());
        String randomCode = Integer.toHexString(new Random().nextInt(0xFFFF));
        return baseName + "_" + timestamp + "_" + randomCode;
    }

    public ResponseEntity<?> processFvu(MultipartFile txtFile, MultipartFile csiFile) {
        try {
            logger.info("Starting FVU processing for files TXT [{}], CSI [{}].",
                    txtFile.getOriginalFilename(), csiFile.getOriginalFilename());

            String baseName = txtFile.getOriginalFilename().replaceAll("\\.[^.]*$", "");
            String uniqueFolderName = generateUniqueFolderName(baseName);

            Path dir = Paths.get(outputDir, uniqueFolderName);
            Files.createDirectories(dir);

            // Save uploaded files locally
            File txt = dir.resolve(txtFile.getOriginalFilename()).toFile();
            File csi = dir.resolve(csiFile.getOriginalFilename()).toFile();
            txtFile.transferTo(txt);
            csiFile.transferTo(csi);

            logger.info("Uploaded input files saved to [{}].", dir.toAbsolutePath());

            // Output file paths
            String errorHtmlPath = dir.resolve(baseName + "_ERROR.html").toString();
            String fvuPath = dir.resolve(baseName + ".fvu").toString();

            // Run govt JAR
            FvuJarRunner.runFvuJar(jarPath,
                    txt.getAbsolutePath(),
                    csi.getAbsolutePath(),
                    dir.toString(),
                    errorHtmlPath,
                    fvuPath,
                    dir.toString());

            logger.info("Completed FVU JAR execution for folder [{}].", uniqueFolderName);

            // Upload ALL files (input + outputs) to S3
            File[] allFiles = dir.toFile().listFiles();
            List<String> uploadedKeys = new ArrayList<>();
            if (allFiles != null) {
                for (File file : allFiles) {
                    String key = uniqueFolderName + "/" + file.getName();
                    s3Client.putObject(
                            PutObjectRequest.builder()
                                    .bucket(s3BucketName)
                                    .key(key)
                                    .build(),
                            RequestBody.fromFile(file)
                    );
                    uploadedKeys.add(key);
                    logger.info("Uploaded file [{}] to S3 key [{}].", file.getName(), key);
                }
            }
            
            CompletableFuture.runAsync(() -> {
                try {
                    File zipFile = new File(dir.toFile(), uniqueFolderName + ".zip");
                    try (FileOutputStream fos = new FileOutputStream(zipFile);
                         ZipOutputStream zos = new ZipOutputStream(fos)) {

                        for (File file : allFiles) {
                            try (FileInputStream fis = new FileInputStream(file)) {
                                zos.putNextEntry(new ZipEntry(file.getName()));
                                byte[] buffer = new byte[4096];
                                int length;
                                while ((length = fis.read(buffer)) > 0) {
                                    zos.write(buffer, 0, length);
                                }
                                zos.closeEntry();
                            }
                        }
                    }

                    String zipKey = uniqueFolderName + "/" + uniqueFolderName + ".zip";
                    s3Client.putObject(
                            PutObjectRequest.builder()
                                    .bucket(s3BucketName)
                                    .key(zipKey)
                                    .build(),
                            RequestBody.fromFile(zipFile)
                    );
                    logger.info("Prebuilt ZIP [{}] uploaded to S3.", zipKey);

                    zipFile.delete();
                } catch (Exception ex) {
                    logger.error("Failed to prebuild ZIP for [{}]: {}", uniqueFolderName, ex.getMessage());
                }
            });
            

            // Cleanup local against the racing main thread to specify some delay considering 2 sec delay for local cleanup
            
            CompletableFuture.runAsync(() -> {
                try {
                    // Add a small delay to be extra safe
                    Thread.sleep(5000);

                    if (allFiles != null) {
                        for (File file : allFiles) {
                            if (file.exists() && !file.getName().endsWith(".zip")) {
                                file.delete();
                            }
                        }
                    }
                    Files.deleteIfExists(dir);
                    logger.info("Local cleanup completed for folder [{}]", dir.toAbsolutePath());
                } catch (Exception cleanupEx) {
                    logger.warn("Cleanup failed for [{}]: {}", dir.toAbsolutePath(), cleanupEx.getMessage());
                }
            });
            // Success response
            return ResponseEntity.ok().body(Map.of(
                    "status", "success",
                    "folder", uniqueFolderName,
                    "s3Keys", uploadedKeys
            ));

        } catch (Exception e) {
            logger.error("Exception during FVU processing: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
        }
    }

    // ✅ S3-based ZIP download
    public byte[] getZipBytes(String folderName) throws Exception {
        logger.info("Fetching prebuilt ZIP for folder [{}] from S3.", folderName);

        String zipKey = folderName + "/" + folderName + ".zip";

        ResponseBytes<GetObjectResponse> objBytes = s3Client.getObjectAsBytes(
                GetObjectRequest.builder()
                        .bucket(s3BucketName)
                        .key(zipKey)
                        .build()
        );

        return objBytes.asByteArray();
    }

    // ✅ S3-based PDF fetch
    public byte[] getPdfBytes(String folderName) throws Exception {
        logger.info("Fetching PDF bytes from S3 for folder [{}].", folderName);

        ListObjectsV2Response listRes = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(s3BucketName)
                        .prefix(folderName + "/")
                        .build()
        );

        Optional<S3Object> pdfObject = listRes.contents().stream()
                .filter(obj -> obj.key().toLowerCase().endsWith(".pdf"))
                .findFirst();

        if (pdfObject.isEmpty()) {
            throw new Exception("No PDF file found in S3 folder: " + folderName);
        }

        ResponseBytes<GetObjectResponse> objBytes = s3Client.getObjectAsBytes(
                GetObjectRequest.builder()
                        .bucket(s3BucketName)
                        .key(pdfObject.get().key())
                        .build()
        );

        return objBytes.asByteArray();
    }
}

