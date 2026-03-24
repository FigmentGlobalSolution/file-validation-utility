package com.example.AutomationJavaUtility.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.AutomationJavaUtility.Configuration.S3ClientFactory;
import com.example.AutomationJavaUtility.Utils.FvuJarRunner;
import com.example.AutomationJavaUtility.Utils.ZipUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

@Service
public class FvuService {
	private static final String S3_PREFIX = "FVU-Utility/";
	private static final Logger logger = LoggerFactory.getLogger(FvuService.class);
	private final ObjectMapper objectMapper = new ObjectMapper();
	
	@Value("${fvu.max.concurrent:5}")
	private int maxConcurrent;
	
	@Autowired
	private Executor taskExecutor;
	
	@Autowired
	private StringRedisTemplate redisTemplate;
	
	private Semaphore semaphore;
	
	@Value("${job.status.ttl.hours:24}")
	private long jobStatusTtlHours;
	
	private static final DateTimeFormatter ITC_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
	private static final ZoneId APP_ZONE = ZoneId.of("Asia/Kolkata");

	@PostConstruct
	public void initSemaphore() {
		this.semaphore = new Semaphore(maxConcurrent, true);
		logger.info("Initialized semaphore with {} max concurrent slots", maxConcurrent);
	}

	private String outputDir;
	private String jarPath;
	private String s3BucketName;
	private final S3Client s3Client;

	@Autowired
	public FvuService(S3ClientFactory factory,
			@Value("${fvu.output.dir}") String outputDir,
			@Value("${fvu.jar.path}") String jarPath) {

		this.s3Client = factory.getClient();
		this.s3BucketName = factory.resolveBucket();
		this.outputDir = outputDir;
		this.jarPath = jarPath;

		logger.info("Storage client initialized successfully.");
	}

	private String generateUniqueFolderName(String baseName) {
		String timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now());
		String randomCode = Integer.toHexString(new Random().nextInt(0xFFFF));
		return baseName + "_" + timestamp + "_" + randomCode;
	}

	/**
	 * ENHANCED: Process FVU files (Regular returns OR Correction statements)
	 * 
	 * @param txtFile - TDS/TCS input file (.txt) - MANDATORY
	 * @param csiFile - Challan file (.csi) - MANDATORY for regular, OPTIONAL for correction
	 * @param consolidateFile - Consolidate file (.tds) - NULL for regular, MANDATORY for correction
	 * @param clientCode - Client identifier
	 * @param tanNo - TAN number
	 */
	public ResponseEntity<?> processFvuAsync(
			MultipartFile txtFile,
			MultipartFile csiFile,
			MultipartFile consolidateFile,
			String clientCode,
			String tanNo) {

		try {
			String baseName = txtFile.getOriginalFilename().replaceAll("\\.[^.]*$", "");
			String uniqueFolderName = generateUniqueFolderName(baseName);
			String fullFolderPath = S3_PREFIX + clientCode + "/" + tanNo + "/" + uniqueFolderName;

			// Determine statement type based on consolidate file presence
			boolean isCorrectionStatement = (consolidateFile != null && !consolidateFile.isEmpty());

			// Store job metadata in Redis
			try {
				Map<String, String> jobData = new HashMap<>();
				jobData.put("status", "QUEUED");
				jobData.put("statementType", isCorrectionStatement ? "CORRECTION" : "REGULAR");
				jobData.put("txtFileName", txtFile.getOriginalFilename());
				jobData.put("csiFileName", csiFile != null ? csiFile.getOriginalFilename() : "NOT_PROVIDED");
				jobData.put("consolidateFileName",
						consolidateFile != null ? consolidateFile.getOriginalFilename() : "NOT_APPLICABLE");
				jobData.put("startTime", LocalDateTime.now(APP_ZONE).format(ITC_FORMAT));

				redisTemplate.opsForValue().set(
						getRedisKey(fullFolderPath),
						objectMapper.writeValueAsString(jobData),
						Duration.ofHours(jobStatusTtlHours));
			} catch (Exception redisEx) {
				logger.error("Redis unavailable. Continuing without status tracking.", redisEx);
			}

			logger.info("Request accepted for folder [{}]. Type: {}",
					fullFolderPath, isCorrectionStatement ? "CORRECTION" : "REGULAR");

			// Create working directory
			Path dir = Paths.get(outputDir, uniqueFolderName);
			Files.createDirectories(dir);

			// Persist TXT file (MANDATORY)
			// Persist TXT file (MANDATORY) - RAW BYTE COPY
			File txt = dir.resolve(txtFile.getOriginalFilename()).toFile();
//			Files.copy(
//			        txtFile.getInputStream(),
//			        txt.toPath(),
//			        java.nio.file.StandardCopyOption.REPLACE_EXISTING
//			);
			txtFile.transferTo(txt);
			String txtPath = txt.getAbsolutePath();
			String txtFileName = txtFile.getOriginalFilename();

			// Persist CSI file (if present)
			String csiPath = null;
			String csiFileName = null;
			if (csiFile != null && !csiFile.isEmpty()) {
				File csi = dir.resolve(csiFile.getOriginalFilename()).toFile();
//				Files.copy(
//				        csiFile.getInputStream(),
//				        csi.toPath(),
//				        java.nio.file.StandardCopyOption.REPLACE_EXISTING
//				);
				csiFile.transferTo(csi);
				csiPath = csi.getAbsolutePath();
				csiFileName = csiFile.getOriginalFilename();
			}

			// Persist Consolidate file (if present - for corrections)
			String consolidatePath = null;
			String consolidateFileName = null;
			if (consolidateFile != null && !consolidateFile.isEmpty()) {
				File consolidate = dir.resolve(consolidateFile.getOriginalFilename()).toFile();
//				Files.copy(
//				        consolidateFile.getInputStream(),
//				        consolidate.toPath(),
//				        java.nio.file.StandardCopyOption.REPLACE_EXISTING
//				);
				consolidateFile.transferTo(consolidate);
				consolidatePath = consolidate.getAbsolutePath();
				consolidateFileName = consolidateFile.getOriginalFilename();
				logger.info("Consolidate file persisted: [{}]", consolidateFileName);
			}

			// Start background processing
			final String finalCsiPath = csiPath;
			final String finalCsiFileName = csiFileName;
			final String finalConsolidatePath = consolidatePath;
			final String finalConsolidateFileName = consolidateFileName;
			final boolean finalIsCorrectionStatement = isCorrectionStatement;

			CompletableFuture.runAsync(() -> {
				boolean acquired = false;

				try {
					// Wait for semaphore slot
					semaphore.acquire();
					updateJobStatus(fullFolderPath, "RUNNING");
					acquired = true;

					logger.info("Permit acquired. Starting execution for [{}]", uniqueFolderName);

					processFvuInBackground(
							txtPath, finalCsiPath, finalConsolidatePath,
							txtFileName, finalCsiFileName, finalConsolidateFileName,
							clientCode, tanNo, uniqueFolderName, dir,
							finalIsCorrectionStatement);

				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					logger.error("Interrupted while waiting for semaphore", e);
				} finally {
					if (acquired) {
						semaphore.release();
						logger.info("Semaphore released for [{}]", uniqueFolderName);
					}
				}
			}, taskExecutor);

			return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
					"status", "QUEUED",
					"statementType", isCorrectionStatement ? "CORRECTION" : "REGULAR",
					"folderPath", fullFolderPath,
					"message", "Request accepted and queued. It will start automatically when a slot becomes available."));

		} catch (Exception e) {
			logger.error("Failed to initiate processing: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body("Error initiating processing: " + e.getMessage());
		}
	}

	private void processFvuInBackground(
			String txtPath,
			String csiPath,
			String consolidatePath,
			String txtFileName,
			String csiFileName,
			String consolidateFileName,
			String clientCode,
			String tanNo,
			String uniqueFolderName,
			Path dir,
			boolean isCorrectionStatement) {

		try {
			logger.info("Starting background FVU processing for files TXT [{}], CSI [{}], Consolidate [{}]. Type: {}",
					txtFileName,
					csiFileName != null ? csiFileName : "NONE",
					consolidateFileName != null ? consolidateFileName : "NONE",
					isCorrectionStatement ? "CORRECTION" : "REGULAR");

			String baseName = txtFileName.replaceAll("\\.[^.]*$", "");
			String errorHtmlPath = dir.resolve(baseName + "err.html").toString();
			String fvuPath = dir.resolve(baseName + ".fvu").toString();

			// Run government JAR with appropriate arguments
			FvuJarRunner.runFvuJar(
					jarPath,
					txtPath,
					csiPath,
					consolidatePath,
					dir.toString(),
					errorHtmlPath,
					fvuPath,
					dir.toString(),
					isCorrectionStatement);

			logger.info("Completed FVU JAR execution for folder [{}].", uniqueFolderName);

			// Upload ALL files to S3
			File[] allFiles = dir.toFile().listFiles();
			List<String> uploadedKeys = new ArrayList<>();
			/*
			 * if (allFiles != null) { for (File file : allFiles) { String key = S3_PREFIX +
			 * clientCode + "/" + tanNo + "/" + uniqueFolderName + "/" + file.getName();
			 * s3Client.putObject(
			 * PutObjectRequest.builder().bucket(s3BucketName).key(key).build(),
			 * RequestBody.fromFile(file)); uploadedKeys.add(key);
			 * logger.info("Uploaded file [{}] to S3 key [{}].", file.getName(), key); } }
			 */
			
			if (allFiles != null) {

			    Arrays.stream(allFiles)
			            .parallel()
			            .forEach(file -> {

			                String key = S3_PREFIX + clientCode + "/" + tanNo + "/" + uniqueFolderName + "/" + file.getName();

			                s3Client.putObject(
			                        PutObjectRequest.builder()
			                                .bucket(s3BucketName)
			                                .key(key)
			                                .build(),
			                        RequestBody.fromFile(file));

			                logger.info("Uploaded file [{}] to S3 key [{}].", file.getName(), key);
			            });

			}
			

			// Create and upload ZIP
			CompletableFuture.runAsync(() -> {
				try {
					String zipFilePath = dir.resolve(uniqueFolderName + ".zip").toString();
					File zipFile = ZipUtils.zipFolder(dir.toFile(), zipFilePath);

					String zipKey = S3_PREFIX + clientCode + "/" + tanNo + "/" + uniqueFolderName + "/"
							+ uniqueFolderName + ".zip";
					s3Client.putObject(
							PutObjectRequest.builder().bucket(s3BucketName).key(zipKey).build(),
							RequestBody.fromFile(zipFile));
					logger.info("Prebuilt ZIP [{}] uploaded to S3.", zipKey);

					zipFile.delete();

					try {
						updateJobStatus(
								S3_PREFIX + clientCode + "/" + tanNo + "/" + uniqueFolderName,
								"COMPLETED");
					} catch (Exception redisEx) {
						logger.error("Redis unavailable. Continuing without status tracking.", redisEx);
					}
				} catch (Exception ex) {
					logger.error("Failed to prebuild ZIP for [{}]", uniqueFolderName, ex);

					try {
						updateJobStatus(
								S3_PREFIX + clientCode + "/" + tanNo + "/" + uniqueFolderName,
								"FAILED");
					} catch (Exception redisEx) {
						logger.error("Redis unavailable while marking FAILED.", redisEx);
					}
				}
			}).thenRun(() -> {
				try {
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

			logger.info("Background processing completed successfully for folder [{}].", uniqueFolderName);

		} catch (Exception e) {
			logger.error("Exception during background FVU processing: {}", e.getMessage(), e);
			try {
				updateJobStatus(
						S3_PREFIX + clientCode + "/" + tanNo + "/" + uniqueFolderName,
						"FAILED");
			} catch (Exception redisEx) {
				logger.error("Redis unavailable. Continuing without status tracking.", redisEx);
			}
		}
	}

	public ResponseEntity<?> checkFolderStatus(String folderPath) {
		try {
			logger.info("Checking status for folder path [{}].", folderPath);

			String jobJson = redisTemplate.opsForValue().get(getRedisKey(folderPath));

			if (jobJson == null) {
				return ResponseEntity.ok().body(Map.of(
						"status", "UNKNOWN",
						"message", "No job found for this folder."));
			}

			Map<String, String> jobData = objectMapper.readValue(jobJson, Map.class);

			String status = jobData.get("status");
			String statementType = jobData.getOrDefault("statementType", "REGULAR");
			String txtFileName = jobData.get("txtFileName");
			String csiFileName = jobData.get("csiFileName");
			String consolidateFileName = jobData.getOrDefault("consolidateFileName", "NOT_APPLICABLE");

			if (!"COMPLETED".equals(status)) {
				return ResponseEntity.ok().body(Map.of(
						"status", status,
						"statementType", statementType,
						"txtFileName", txtFileName,
						"csiFileName", csiFileName,
						"consolidateFileName", consolidateFileName,
						"folderPath", folderPath));
			}

			// Status is COMPLETED - check S3 for files
			ListObjectsV2Response listRes = s3Client.listObjectsV2(
					ListObjectsV2Request.builder()
							.bucket(s3BucketName)
							.prefix(folderPath + "/")
							.build());

			List<S3Object> objects = listRes.contents();

			if (objects.isEmpty()) {
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
						"status", "ERROR",
						"message", "Job marked COMPLETED but no files found in S3."));
			}

			boolean zipExists = false;
			boolean pdfExists = false;
			String txtFilePath = null;
			String csiFilePath = null;
			String consolidateFilePath = null;

			for (S3Object obj : objects) {
				String key = obj.key().toLowerCase();

				if (key.endsWith(".zip")) {
					zipExists = true;
				}
				if (key.endsWith(".pdf")) {
					pdfExists = true;
				}
				if (key.endsWith(".txt")) {
					txtFilePath = obj.key();
				}
				if (key.endsWith(".csi")) {
					csiFilePath = obj.key();
				}
				if (key.endsWith(".tds")) {
					consolidateFilePath = obj.key();
				}
			}

			if (zipExists) {
				String fileStatus = pdfExists ? "S" : "F";

				Map<String, Object> responseData = new HashMap<>();
				responseData.put("status", "COMPLETED");
				responseData.put("statementType", statementType);
				responseData.put("message", "Processing completed successfully.");
				responseData.put("fileCount", objects.size());
				responseData.put("zipAvailable", true);
				responseData.put("pdfAvailable", pdfExists);
				responseData.put("folderPath", folderPath);
				responseData.put("textFilePath", txtFilePath);
				responseData.put("csiFilePath", csiFilePath);
				responseData.put("consolidateFilePath", consolidateFilePath);
				responseData.put("FileStatus", fileStatus);

				return ResponseEntity.ok().body(responseData);
			} else {
				return ResponseEntity.ok().body(Map.of(
						"status", "RUNNING",
						"statementType", statementType,
						"message", "Processing in progress. ZIP file not yet ready.",
						"fileCount", objects.size(),
						"pdfAvailable", pdfExists));
			}

		} catch (Exception e) {
			logger.error("Error checking folder status for [{}]: {}", folderPath, e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("status", "error", "message", e.getMessage()));
		}
	}

	private void updateJobStatus(String folderPath, String newStatus) {
		try {
			String key = getRedisKey(folderPath);
			String existing = redisTemplate.opsForValue().get(key);

			if (existing == null) {
				return;
			}

			Map<String, String> jobData = objectMapper.readValue(existing, Map.class);

			jobData.put("status", newStatus);
			if ("COMPLETED".equals(newStatus) || "FAILED".equals(newStatus)) {
				jobData.put("endTime", LocalDateTime.now(APP_ZONE).format(ITC_FORMAT));
			}

			redisTemplate.opsForValue().set(
					key,
					objectMapper.writeValueAsString(jobData),
					Duration.ofHours(jobStatusTtlHours));

		} catch (Exception e) {
			logger.error("Failed to update job status to {}", newStatus, e);
		}
	}

	public byte[] getZipBytes(String folderPath) throws Exception {
		logger.info("Fetching prebuilt ZIP for folder path [{}] from S3.", folderPath);

		String uniqueFolderName = folderPath.substring(folderPath.lastIndexOf("/") + 1);
		String zipKey = folderPath + "/" + uniqueFolderName + ".zip";

		ResponseBytes<GetObjectResponse> objBytes = s3Client.getObjectAsBytes(
				GetObjectRequest.builder()
						.bucket(s3BucketName)
						.key(zipKey)
						.build());

		return objBytes.asByteArray();
	}

	public ResponseEntity<?> deleteRequestFolder(String clientCode, String tanNo, String reqId) {
		try {
			String folderPrefix = S3_PREFIX + clientCode + "/" + tanNo + "/" + reqId + "/";

			logger.info("Deleting S3 folder with prefix [{}]", folderPrefix);

			ListObjectsV2Response listResponse = s3Client.listObjectsV2(
					ListObjectsV2Request.builder()
							.bucket(s3BucketName)
							.prefix(folderPrefix)
							.build());

			List<S3Object> objects = listResponse.contents();

			if (objects.isEmpty()) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND)
						.body(Map.of(
								"status", 0,
								"message", "No files found for given request ID."));
			}

			// Delete each object
			for (S3Object object : objects) {
				s3Client.deleteObject(builder -> builder
						.bucket(s3BucketName)
						.key(object.key()));
				logger.info("Deleted S3 object [{}]", object.key());
			}

			// Delete Redis job entry
			try {
				String redisKey = getRedisKey(S3_PREFIX + clientCode + "/" + tanNo + "/" + reqId);
				redisTemplate.delete(redisKey);
			} catch (Exception redisEx) {
				logger.warn("Redis cleanup failed but S3 deletion completed.");
			}

			return ResponseEntity.ok(Map.of(
					"status", 1,
					"message", "All files and folder deleted successfully.",
					"folderPath", folderPrefix));

		} catch (Exception e) {
			logger.error("Error deleting folder: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of(
							"status", 0,
							"message", e.getMessage()));
		}
	}

	public byte[] getPdfBytes(String folderPath) throws Exception {
		logger.info("Fetching PDF bytes from S3 for folder path [{}].", folderPath);

		ListObjectsV2Response listRes = s3Client.listObjectsV2(
				ListObjectsV2Request.builder()
						.bucket(s3BucketName)
						.prefix(folderPath + "/")
						.build());

		Optional<S3Object> pdfObject = listRes.contents().stream()
				.filter(obj -> obj.key().toLowerCase().endsWith(".pdf"))
				.findFirst();

		if (pdfObject.isEmpty()) {
			throw new Exception("No PDF file found in S3 folder: " + folderPath);
		}

		ResponseBytes<GetObjectResponse> objBytes = s3Client.getObjectAsBytes(
				GetObjectRequest.builder()
						.bucket(s3BucketName)
						.key(pdfObject.get().key())
						.build());

		return objBytes.asByteArray();
	}

	private String getRedisKey(String folderPath) {
		return "FVU:JOB:" + folderPath;
	}
}