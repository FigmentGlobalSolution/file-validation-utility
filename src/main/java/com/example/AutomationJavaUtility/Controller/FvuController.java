package com.example.AutomationJavaUtility.Controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.AutomationJavaUtility.Service.FvuService;
import com.example.AutomationJavaUtility.model.ApiResponse;

@RestController
@RequestMapping("/api/fvu")
public class FvuController {

	private static final Logger logger = LoggerFactory.getLogger(FvuController.class);
	@Autowired
	private FvuService fvuService;

	/**
	 * EXISTING ENDPOINT - Regular TDS/TCS Returns (Unchanged)
	 */
	@PostMapping("/validate")
	public ResponseEntity<?> validateFvu(
			@RequestParam(value = "txtFile", required = false) MultipartFile txtFile,
			@RequestParam(value = "csiFile", required = false) MultipartFile csiFile,
			@RequestParam(value = "clientCode", required = false) String clientCode,
			@RequestParam(value = "tanNo", required = false) String tanNo) {

		logger.info("Received request to validate FVU files.");

		// ✅ Validate Client Code
		if (clientCode == null || clientCode.trim().isEmpty()) {
			logger.warn("Validation failed: Client Code is missing.");
			return badRequest("Client Code is required.");
		}
		if (!clientCode.matches("^[a-zA-Z0-9_-]+$")) {
			logger.warn("Validation failed: Client Code [{}] contains invalid characters.", clientCode);
			return badRequest("Client Code must not contain special characters except _ and -.");
		}

		// ✅ Validate TAN No
		if (tanNo == null || tanNo.trim().isEmpty()) {
			logger.warn("Validation failed: TAN No is missing.");
			return badRequest("TAN No is required.");
		}
		if (!tanNo.matches("^[a-zA-Z0-9_-]+$")) {
			logger.warn("Validation failed: TAN No [{}] contains invalid characters.", tanNo);
			return badRequest("TAN No must not contain special characters except _ and -.");
		}

		// ✅ Check if both files are provided
		if (txtFile == null || txtFile.isEmpty() || csiFile == null || csiFile.isEmpty()) {
			logger.warn("Validation failed: Missing TXT or CSI file.");
			return badRequest("Both .txt and .csi files are required.");
		}

		// ✅ Validate TXT File
		ResponseEntity<?> txtValidation = validateTxtFile(txtFile);
		if (txtValidation != null) return txtValidation;

		// ✅ Validate CSI File
		ResponseEntity<?> csiValidation = validateCsiFile(csiFile);
		if (csiValidation != null) return csiValidation;

		logger.info("Validation passed for TXT [{}] and CSI [{}]. Forwarding to service.",
				txtFile.getOriginalFilename(), csiFile.getOriginalFilename());

		// ✅ Process as regular return (no consolidate file)
		try {
			ResponseEntity<?> response = fvuService.processFvuAsync(
					txtFile, csiFile, null, clientCode, tanNo);
			logger.info("Background processing initiated for files TXT [{}], CSI [{}], ClientCode [{}], TAN [{}].",
					txtFile.getOriginalFilename(), csiFile.getOriginalFilename(), clientCode, tanNo);
			return response;
		} catch (Exception e) {
			logger.error("Failed to initiate processing for TXT [{}], CSI [{}]. Error: {}",
					txtFile.getOriginalFilename(), csiFile.getOriginalFilename(), e.getMessage(), e);
			return serverError("Error while initiating processing: " + e.getMessage());
		}
	}

	/**
	 * NEW ENDPOINT - Correction Statements (with optional CSI and mandatory Consolidate)
	 */
	@PostMapping("/validate-correction")
	public ResponseEntity<?> validateCorrectionFvu(
			@RequestParam(value = "txtFile", required = false) MultipartFile txtFile,
			@RequestParam(value = "csiFile", required = false) MultipartFile csiFile,
			@RequestParam(value = "consolidateFile", required = false) MultipartFile consolidateFile,
			@RequestParam(value = "clientCode", required = false) String clientCode,
			@RequestParam(value = "tanNo", required = false) String tanNo) {

		logger.info("Received request to validate CORRECTION FVU files.");

		// ✅ Validate Client Code
		if (clientCode == null || clientCode.trim().isEmpty()) {
			logger.warn("Validation failed: Client Code is missing.");
			return badRequest("Client Code is required.");
		}
		if (!clientCode.matches("^[a-zA-Z0-9_-]+$")) {
			logger.warn("Validation failed: Client Code [{}] contains invalid characters.", clientCode);
			return badRequest("Client Code must not contain special characters except _ and -.");
		}

		// ✅ Validate TAN No
		if (tanNo == null || tanNo.trim().isEmpty()) {
			logger.warn("Validation failed: TAN No is missing.");
			return badRequest("TAN No is required.");
		}
		if (!tanNo.matches("^[a-zA-Z0-9_-]+$")) {
			logger.warn("Validation failed: TAN No [{}] contains invalid characters.", tanNo);
			return badRequest("TAN No must not contain special characters except _ and -.");
		}

		// ✅ Check TXT file (MANDATORY)
		if (txtFile == null || txtFile.isEmpty()) {
			logger.warn("Validation failed: Missing TXT file.");
			return badRequest("TXT file is required for correction statements.");
		}

		// ✅ Check Consolidate file (MANDATORY for corrections)
		if (consolidateFile == null || consolidateFile.isEmpty()) {
			logger.warn("Validation failed: Missing consolidate file.");
			return badRequest("Consolidate file is MANDATORY for correction statements.");
		}

		// ✅ Validate TXT File
		ResponseEntity<?> txtValidation = validateTxtFile(txtFile);
		if (txtValidation != null) return txtValidation;

		// ✅ Validate Consolidate File
		ResponseEntity<?> consolidateValidation = validateConsolidateFile(consolidateFile);
		if (consolidateValidation != null) return consolidateValidation;

		// ✅ Validate CSI File (if present - OPTIONAL for corrections)
		if (csiFile != null && !csiFile.isEmpty()) {
			ResponseEntity<?> csiValidation = validateCsiFile(csiFile);
			if (csiValidation != null) return csiValidation;
			logger.info("CSI file provided: [{}]", csiFile.getOriginalFilename());
		} else {
			logger.info("No CSI file provided (optional for corrections).");
		}

		logger.info("Validation passed for CORRECTION - TXT [{}], Consolidate [{}], CSI [{}]",
				txtFile.getOriginalFilename(),
				consolidateFile.getOriginalFilename(),
				csiFile != null ? csiFile.getOriginalFilename() : "NOT_PROVIDED");

		// ✅ Process correction statement
		try {
			ResponseEntity<?> response = fvuService.processFvuAsync(
					txtFile, csiFile, consolidateFile, clientCode, tanNo);
			logger.info("Background processing initiated for CORRECTION - TXT [{}], Consolidate [{}], CSI [{}], ClientCode [{}], TAN [{}].",
					txtFile.getOriginalFilename(),
					consolidateFile.getOriginalFilename(),
					csiFile != null ? csiFile.getOriginalFilename() : "NONE",
					clientCode, tanNo);
			return response;
		} catch (Exception e) {
			logger.error("Failed to initiate correction processing. Error: {}", e.getMessage(), e);
			return serverError("Error while initiating correction processing: " + e.getMessage());
		}
	}

	/**
	 * Validate TXT file
	 */
	private ResponseEntity<?> validateTxtFile(MultipartFile txtFile) {
		String txtFileName = txtFile.getOriginalFilename();

		if (txtFileName == null || txtFileName.trim().isEmpty()) {
			logger.warn("Validation failed: TXT filename is empty.");
			return badRequest("TDS/TCS Input file name is required.");
		}

		if (!txtFileName.toLowerCase().endsWith(".txt")) {
			logger.warn("Validation failed: TXT file [{}] does not end with .txt", txtFileName);
			return badRequest("TDS/TCS Input file must have .txt extension.");
		}

		if (txtFileName.length() > 12) {
			logger.warn("Validation failed: TXT file [{}] exceeds 12 characters.", txtFileName);
			return badRequest("TDS/TCS Input filename must not exceed 12 characters (including .txt).");
		}

		if (!txtFileName.matches("^[a-zA-Z0-9_.-]+\\.txt$")) {
			logger.warn("Validation failed: TXT file [{}] contains invalid characters.", txtFileName);
			return badRequest("TDS/TCS Input filename must not contain special characters.");
		}

		return null; // Valid
	}

	/**
	 * Validate CSI file
	 */
	private ResponseEntity<?> validateCsiFile(MultipartFile csiFile) {
		String csiFileName = csiFile.getOriginalFilename();

		if (csiFileName == null || csiFileName.trim().isEmpty()) {
			logger.warn("Validation failed: CSI filename is empty.");
			return badRequest("Challan Input file name is required.");
		}

		if (!csiFileName.toLowerCase().endsWith(".csi")) {
			logger.warn("Validation failed: CSI file [{}] does not end with .csi", csiFileName);
			return badRequest("Challan Input file must have .csi extension.");
		}

		if (csiFileName.length() > 25) {
			logger.warn("Validation failed: CSI file [{}] exceeds 25 characters.", csiFileName);
			return badRequest("Challan Input filename must not exceed 25 characters (including .csi).");
		}

		if (!csiFileName.matches("^[a-zA-Z0-9_.-]+\\.csi$")) {
			logger.warn("Validation failed: CSI file [{}] contains invalid characters.", csiFileName);
			return badRequest("Challan Input filename must not contain special characters.");
		}

		return null; // Valid
	}

	/**
	 * Validate Consolidate file (.tds or .txt extension)
	 */
	private ResponseEntity<?> validateConsolidateFile(MultipartFile consolidateFile) {
		String consolidateFileName = consolidateFile.getOriginalFilename();

		if (consolidateFileName == null || consolidateFileName.trim().isEmpty()) {
			logger.warn("Validation failed: Consolidate filename is empty.");
			return badRequest("Consolidate file name is required.");
		}

		// Consolidate files typically have .tds extension (or could be .txt)
		if (!consolidateFileName.toLowerCase().endsWith(".tds") &&
				!consolidateFileName.toLowerCase().endsWith(".txt")) {
			logger.warn("Validation failed: Consolidate file [{}] has invalid extension.", consolidateFileName);
			return badRequest("Consolidate file must have .tds or .txt extension.");
		}

		// Consolidate files can be longer - typically up to 25 characters
		/*
		 * if (consolidateFileName.length() > 25) {
		 * logger.warn("Validation failed: Consolidate file [{}] exceeds 25 characters."
		 * , consolidateFileName); return
		 * badRequest("Consolidate filename must not exceed 25 characters (including extension)."
		 * ); }
		 */

		if (!consolidateFileName.matches("^[a-zA-Z0-9_.-]+\\.(tds|txt|TDS|TXT)$")) {
			logger.warn("Validation failed: Consolidate file [{}] contains invalid characters.", consolidateFileName);
			return badRequest("Consolidate filename must not contain special characters.");
		}

		return null; // Valid
	}

	@GetMapping("/status")
	public ResponseEntity<?> checkStatus(@RequestParam String folderPath) {
		logger.info("Received request to check status for folder [{}].", folderPath);
		try {
			return fvuService.checkFolderStatus(folderPath);
		} catch (Exception e) {
			logger.error("Failed to check status for folder [{}]. Error: {}", folderPath, e.getMessage(), e);
			return ResponseEntity.badRequest().body("Error checking status: " + e.getMessage());
		}
	}

	@GetMapping("/getPDF")
	public ResponseEntity<?> getPdfFromFolder(@RequestParam String folderPath) {
		logger.info("Received request to fetch PDF for folder [{}].", folderPath);

		try {
			byte[] pdfBytes = fvuService.getPdfBytes(folderPath);
			logger.info("Successfully fetched PDF for folder [{}].", folderPath);

			return ResponseEntity.ok()
					.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"fvu-report.pdf\"")
					.contentType(MediaType.APPLICATION_PDF)
					.body(pdfBytes);

		} catch (Exception e) {
			logger.error("Failed to fetch PDF for folder [{}]. Error: {}", folderPath, e.getMessage(), e);
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}

	@GetMapping("/getZip")
	public ResponseEntity<?> getZip(@RequestParam String folderPath) {
		logger.info("Received request to fetch ZIP for folder [{}].", folderPath);

		try {
			byte[] zipBytes = fvuService.getZipBytes(folderPath);
			String uniqueFolderName = folderPath.substring(folderPath.lastIndexOf("/") + 1);

			return ResponseEntity.ok()
					.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + uniqueFolderName + ".zip\"")
					.contentType(MediaType.APPLICATION_OCTET_STREAM)
					.body(zipBytes);
		} catch (Exception e) {
			logger.error("Failed to fetch ZIP for folder [{}]. Error: {}", folderPath, e.getMessage(), e);
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}

	@DeleteMapping("/delete")
	public ResponseEntity<?> deleteRequestFolder(
			@RequestParam(value = "clientCode", required = true) String clientCode,
			@RequestParam(value = "tanNo", required = true) String tanNo,
			@RequestParam(value = "reqId", required = true) String reqId) {

		logger.info("Received delete request for Client [{}], TAN [{}], ReqId [{}]",
				clientCode, tanNo, reqId);

		// ✅ Validate Client Code
		if (clientCode.trim().isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of(
					"status", 0,
					"message", "Client Code is required."));
		}

		if (!clientCode.matches("^[a-zA-Z0-9_-]+$")) {
			return ResponseEntity.badRequest().body(Map.of(
					"status", 0,
					"message", "Client Code must not contain special characters except _ and -."));
		}

		// ✅ Validate TAN No
		if (tanNo.trim().isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of(
					"status", 0,
					"message", "TAN No is required."));
		}

		if (!tanNo.matches("^[a-zA-Z0-9_-]+$")) {
			return ResponseEntity.badRequest().body(Map.of(
					"status", 0,
					"message", "TAN No must not contain special characters except _ and -."));
		}

		// ✅ Validate ReqId
		if (reqId.trim().isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of(
					"status", 0,
					"message", "ReqId is required."));
		}

		if (!reqId.matches("^[a-zA-Z0-9_-]+$")) {
			return ResponseEntity.badRequest().body(Map.of(
					"status", 0,
					"message", "ReqId must not contain special characters except _ and -."));
		}

		try {
			return fvuService.deleteRequestFolder(clientCode, tanNo, reqId);
		} catch (Exception e) {
			logger.error("Delete failed: {}", e.getMessage(), e);
			return ResponseEntity.internalServerError().body(Map.of(
					"status", 0,
					"message", "Error deleting folder: " + e.getMessage()));
		}
	}

	private ResponseEntity<?> badRequest(String message) {
		return ResponseEntity
				.badRequest()
				.body(new ApiResponse(0, message));
	}

	private ResponseEntity<?> serverError(String message) {
		return ResponseEntity
				.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new ApiResponse(0, message));
	}

	private ResponseEntity<?> success(String message, Object data) {
		return ResponseEntity
				.ok()
				.body(new ApiResponse(1, message, data));
	}
}