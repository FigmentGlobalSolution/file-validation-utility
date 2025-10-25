package com.example.AutomationJavaUtility.Controller;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.AutomationJavaUtility.Service.FvuService;

@RestController
@RequestMapping("/api/fvu")
public class FvuController {

	private static final Logger logger=LoggerFactory.getLogger(FvuController.class);
	@Autowired
	private FvuService fvuService;

	@PostMapping("/validate")
	public ResponseEntity<?> validateFvu(@RequestParam(value = "txtFile", required = false) MultipartFile txtFile,
			@RequestParam(value = "csiFile", required = false) MultipartFile csiFile) {

		logger.info("Received request to validate FVU files.");
		// ✅ Check if both files are provided
		if (txtFile == null || txtFile.isEmpty() || csiFile == null || csiFile.isEmpty()) {
			logger.warn("Validation failed: Missing TXT or CSI file.");
			return ResponseEntity.badRequest().body("Both .txt and .csi files are required.");
		}

		// ✅ Validate TXT File
		String txtFileName = txtFile.getOriginalFilename();
		if (txtFileName == null || txtFileName.trim().isEmpty()) {
			logger.warn("Validation failed: TXT filename is empty.");
			return ResponseEntity.badRequest().body("TDS/TCS Input file name is required.");
		}
		if (!txtFileName.toLowerCase().endsWith(".txt")) {
			logger.warn("Validation failed: TXT file [{}] does not end with .txt", txtFileName);

			return ResponseEntity.badRequest().body("TDS/TCS Input file must have .txt extension.");
		}
		if (txtFileName.length() > 12) {
			logger.warn("Validation failed: TXT file [{}] does not end with .txt", txtFileName);

			return ResponseEntity.badRequest()
					.body("TDS/TCS Input filename must not exceed 12 characters (including .txt).");
		}
		if (!txtFileName.matches("^[a-zA-Z0-9_.-]+\\.txt$")) {
			logger.warn("Validation failed: TXT file [{}] contains invalid characters.", txtFileName);

			return ResponseEntity.badRequest().body("TDS/TCS Input filename must not contain special characters.");
		}

		// ✅ Validate CSI File
		String csiFileName = csiFile.getOriginalFilename();
		if (csiFileName == null || csiFileName.trim().isEmpty()) {
			logger.warn("Validation failed: CSI filename is empty.");

			return ResponseEntity.badRequest().body("Challan Input file name is required.");
		}
		if (!csiFileName.toLowerCase().endsWith(".csi")) {
			logger.warn("Validation failed: CSI file [{}] does not end with .csi", csiFileName);

			return ResponseEntity.badRequest().body("Challan Input file must have .csi extension.");
		}
		if (csiFileName.length() > 25) {
			logger.warn("Validation failed: CSI file [{}] exceeds 25 characters.", csiFileName);

			return ResponseEntity.badRequest()
					.body("Challan Input filename must not exceed 25 characters (including .csi).");
		}
		if (!csiFileName.matches("^[a-zA-Z0-9_.-]+\\.csi$")) {
			logger.warn("Validation failed: CSI file [{}] contains invalid characters.", csiFileName);

			return ResponseEntity.badRequest().body("Challan Input filename must not contain special characters.");
		}
		logger.info("Validation passed for TXT [{}] and CSI [{}]. Forwarding to service.", txtFileName, csiFileName);


		// ✅ If all validations pass, continue to service
		try {
			ResponseEntity<?> response = fvuService.processFvu(txtFile, csiFile);
			logger.info("Service processing completed successfully for files TXT [{}], CSI [{}].", txtFileName,
					csiFileName);
			return response;
		} catch (Exception e) {
			logger.error("Service processing failed for TXT [{}], CSI [{}]. Error: {}", txtFileName, csiFileName,
					e.getMessage(), e);
			return ResponseEntity.internalServerError().body("Error while processing files: " + e.getMessage());
		}
	}

	@GetMapping("getPDF/{folderName}")
	public ResponseEntity<?> getPdfFromFolder(@PathVariable String folderName) {
		logger.info("Received request to fetch PDF for folder [{}].", folderName);

		try {
			byte[] pdfBytes = fvuService.getPdfBytes(folderName);
			logger.info("Successfully fetched PDF for folder [{}].", folderName);


			return ResponseEntity.ok()
					.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + folderName + ".pdf\"")
					.contentType(MediaType.APPLICATION_PDF).body(pdfBytes);

		} catch (Exception e) {
			logger.error("Failed to fetch PDF for folder [{}]. Error: {}", folderName, e.getMessage(), e);

			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}
	
	//Zip Method for Deployment
	@GetMapping("/getZip/{folderName}")
	public ResponseEntity<?> getZip(@PathVariable String folderName) {
		try {
			byte[] zipBytes = fvuService.getZipBytes(folderName);
			return ResponseEntity.ok()
					.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + folderName + ".zip\"")
					.contentType(MediaType.APPLICATION_OCTET_STREAM).body(zipBytes);
		} catch (Exception e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}
}
