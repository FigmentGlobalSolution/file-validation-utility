package com.example.AutomationJavaUtility.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import org.springframework.http.HttpStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.concurrent.Semaphore;
import com.example.AutomationJavaUtility.Utils.FvuJarRunner;

import jakarta.annotation.PostConstruct;

//import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
//import software.amazon.awssdk.regions.Region;
//import software.amazon.awssdk.services.s3.S3Client;
//import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class FvuServiceOrig {

	private static final Logger logger=LoggerFactory.getLogger(FvuServiceOrig.class);
	
	
	@Value("${fvu.max.concurrent:10}")
	private int maxConcurrent;

	private Semaphore semaphore;

	@PostConstruct
	public void initSemaphore() {
		this.semaphore = new Semaphore(maxConcurrent, true); // fair queueing
		logger.info("Initialized semaphore with {} max concurrent slots", maxConcurrent);
	}
    @Value("${fvu.output.dir}")
    private String outputDir;

    @Value("${fvu.jar.path}")
    private String jarPath;

//    @Value("${s3.bucket.name}")
//    private String s3BucketName;
//
//    private final S3Client s3Client;
//
//    DefaultCredentialsProvider defaultCredentialsProvider = DefaultCredentialsProvider
//            .builder().build();
//    public FvuService(@Value("${aws.region}") String region) {
//        this.s3Client = S3Client.builder()
//                .region(Region.of(region))
//                .credentialsProvider(defaultCredentialsProvider)
//                .build();
//    }

    private String generateUniqueFolderName(String baseName) {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now());
        String randomCode = Integer.toHexString(new Random().nextInt(0xFFFF));
        return baseName + "_" + timestamp + "_" + randomCode;
    }

    //Modify the code to retunr zip using the ziputils
    public ResponseEntity<?> processFvu(MultipartFile txtFile, MultipartFile csiFile) {
        try {
        	logger.info("Starting FVU processing for files TXT [{}], CSI [{}].", txtFile.getOriginalFilename(),
					csiFile.getOriginalFilename());
            // Base name
            String baseName = txtFile.getOriginalFilename().replaceAll("\\.[^.]*$", "");
            String uniqueFolderName = generateUniqueFolderName(baseName);

            // Create local folder
            Path dir = Paths.get(outputDir, uniqueFolderName);
            Files.createDirectories(dir);
			logger.debug("Created unique output directory [{}].", dir.toAbsolutePath());


            // Save uploaded files
            File txt = dir.resolve(txtFile.getOriginalFilename()).toFile();
            File csi = dir.resolve(csiFile.getOriginalFilename()).toFile();
            txtFile.transferTo(txt);
            csiFile.transferTo(csi);
			logger.info("Uploaded files saved to [{}].", dir.toAbsolutePath());


            // Output paths for FVU
            String errorHtmlPath = dir.resolve(baseName + "_ERROR.html").toString();
            String fvuPath = dir.resolve(baseName + ".fvu").toString();
			logger.info("Executing FVU JAR located at [{}].", jarPath);


            // Run JAR and watch folder
            FvuJarRunner.runFvuJar(jarPath, txt.getAbsolutePath(), csi.getAbsolutePath(),
                    dir.toString(), errorHtmlPath, fvuPath, dir.toString());
			logger.info("Completed FVU JAR execution for folder [{}].", uniqueFolderName);

            // Upload all generated files to S3
			File fvuFile = new File(fvuPath);
			File errorFile = new File(errorHtmlPath);

			boolean isSuccess = fvuFile.exists();
			boolean isError = errorFile.exists();

			// Step 3: Upload files to S3
//			File[] generatedFiles = dir.toFile().listFiles();
//			if (generatedFiles != null) {
//			    for (File file : generatedFiles) {
//			        PutObjectRequest putRequest = PutObjectRequest.builder()
//			                .bucket(s3BucketName)
//			                .key(uniqueFolderName + "/" + file.getName())
//			                .build();
//			        s3Client.putObject(putRequest, file.toPath());
//			    }
//			}

			// Step 4: Cleanup after upload
//			try {
//			    if (generatedFiles != null) {
//			        for (File file : generatedFiles) {
//			            file.delete();
//			        }
//			    }
//			    Files.deleteIfExists(dir);
//			    logger.debug("Cleaned up local directory [{}].", dir.toAbsolutePath());
//			} catch (Exception cleanupEx) {
//			    logger.warn("Failed to cleanup folder [{}]: {}", dir.toAbsolutePath(), cleanupEx.getMessage());
//			}

			// Step 5: Return response based on earlier decision
			if (isSuccess) {
			    logger.info("FVU validation successful for [{}].", uniqueFolderName);
			    return ResponseEntity.ok().body("Validation successful. Folder created: " + uniqueFolderName);
			} else if (isError) {
			    logger.warn("FVU validation failed for [{}].", uniqueFolderName);
			    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Validation failed. Check error file.");
			} else {
			    logger.error("No output produced for [{}].", uniqueFolderName);
			    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("No output generated.");
			}


        } catch (Exception e) {
        	logger.error("Exception during FVU processing: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
		}
    }
    
    
    
    
    
    
    public byte[] getZipBytes(String folderName) throws Exception {
		Path folderPath = Paths.get(outputDir, folderName);
		if (!Files.exists(folderPath) || !Files.isDirectory(folderPath)) {
			throw new Exception("Folder not found: " + folderName);
		}

		File[] zipFiles = folderPath.toFile().listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
		if (zipFiles == null || zipFiles.length == 0) {
			throw new Exception("No ZIP file found in folder: " + folderName);
		}

		return Files.readAllBytes(zipFiles[0].toPath());
	}
    
    
    public byte[] getPdfBytes(String folderName) throws Exception {
		logger.info("Fetching PDF bytes for folder [{}].", folderName);

		Path folderPath = Paths.get(outputDir, folderName);

		if (!Files.exists(folderPath) || !Files.isDirectory(folderPath)) {
			logger.warn("Requested folder [{}] not found.", folderName);
			throw new Exception("Folder not found: " + folderName);
		}

		File[] pdfFiles = folderPath.toFile().listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));

		if (pdfFiles == null || pdfFiles.length == 0) {
			logger.warn("No PDF files found in folder [{}].", folderName);
			throw new Exception("No PDF file found in folder: " + folderName);
		}

		logger.info("PDF file [{}] found and will be returned as bytes.", pdfFiles[0].getAbsolutePath());
		// Read first PDF as bytes
		return Files.readAllBytes(pdfFiles[0].toPath());
	}
    
    //for s3 fetching as local would get cleanup in the process
	/*
	 * public byte[] getPdfBytes(String folderName) throws Exception {
	 * logger.info("Fetching PDF bytes from S3 for folder [{}].", folderName);
	 * 
	 * // 1. List all objects in the folder ListObjectsV2Request listReq =
	 * ListObjectsV2Request.builder() .bucket(s3BucketName) .prefix(folderName +
	 * "/") // prefix ensures we look only in that "folder" .build();
	 * 
	 * ListObjectsV2Response listRes = s3Client.listObjectsV2(listReq);
	 * 
	 * if (listRes.contents().isEmpty()) {
	 * logger.warn("No files found in S3 folder [{}].", folderName); throw new
	 * Exception("No files found in S3 folder: " + folderName); }
	 * 
	 * // 2. Find the first PDF file S3Object pdfObject =
	 * listRes.contents().stream() .filter(obj ->
	 * obj.key().toLowerCase().endsWith(".pdf")) .findFirst() .orElseThrow(() -> {
	 * logger.warn("No PDF files found in S3 folder [{}].", folderName); return new
	 * Exception("No PDF file found in S3 folder: " + folderName); });
	 * 
	 * logger.info("PDF file [{}] found in S3, downloading.", pdfObject.key());
	 * 
	 * // 3. Download as bytes GetObjectRequest getReq = GetObjectRequest.builder()
	 * .bucket(s3BucketName) .key(pdfObject.key()) .build();
	 * 
	 * ResponseBytes<GetObjectResponse> objBytes =
	 * s3Client.getObjectAsBytes(getReq);
	 * 
	 * // 4. Return the byte[] return objBytes.asByteArray(); }
	 */

//    public byte[] getPdfBytes(String folderName) throws Exception {
//		logger.info("Fetching PDF bytes for folder [{}].", folderName);
//
//        Path folderPath = Paths.get(outputDir, folderName);
//        if (!Files.exists(folderPath) || !Files.isDirectory(folderPath)) {
//			logger.warn("Requested folder [{}] not found.", folderName);
//
//            throw new Exception("Folder not found: " + folderName);
//        }
//
//        File[] pdfFiles = folderPath.toFile().listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));
//        if (pdfFiles == null || pdfFiles.length == 0) {
//			logger.warn("No PDF files found in folder [{}].", folderName);
//
//            throw new Exception("No PDF found in folder: " + folderName);
//        }
//		logger.info("PDF file [{}] found and will be returned as bytes.", pdfFiles[0].getAbsolutePath());
//
//
//        return Files.readAllBytes(pdfFiles[0].toPath());
//    }
    
}
