package com.example.AutomationJavaUtility.Utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FvuJarRunner {

	private static final Logger logger = LoggerFactory.getLogger(FvuJarRunner.class);

	/**
	 * ORIGINAL METHOD - For backward compatibility with regular returns
	 * This maintains your existing working flow
	 */
	public static void runFvuJar(String jarPath, String txtPath, String csiPath,
			String outputDir, String errPath, String fvuPath,
			String uniqueFolderPath) throws Exception {

		// Call the enhanced version with default parameters for regular returns
		runFvuJar(jarPath, txtPath, csiPath, null, outputDir, errPath, fvuPath, uniqueFolderPath, false);
	}

	/**
	 * ENHANCED METHOD - Supports both regular returns and corrections
	 * 
	 * @param jarPath - Path to FVU JAR file
	 * @param txtPath - Input TXT file path
	 * @param csiPath - CSI file path (mandatory for regular, optional for corrections)
	 * @param consolidatePath - Consolidate file path (null for regular, required for corrections)
	 * @param outputDir - Output directory
	 * @param errPath - Error HTML output path
	 * @param fvuPath - FVU output path
	 * @param uniqueFolderPath - Unique folder path for monitoring
	 * @param isCorrectionStatement - true for correction, false for regular
	 */
	public static void runFvuJar(
			String jarPath,
			String txtPath,
			String csiPath,
			String consolidatePath,
			String outputDir,
			String errPath,
			String fvuPath,
			String uniqueFolderPath,
			boolean isCorrectionStatement) throws Exception {

		logger.info("Reached at jar run process. Statement Type: {}",
				isCorrectionStatement ? "CORRECTION" : "REGULAR");

		logger.info("Starting FVU execution. Mode: {}",
                isCorrectionStatement ? "CORRECTION" : "REGULAR");

        String version = "9.3";

        List<String> command = new ArrayList<>();

        // Use xvfb for Swing UI dependency
        command.add("xvfb-run");
        command.add("-a");

        // Use system Java (Java 8 should be default inside container)
        command.add("java");

        // Use CLASSPATH mode (supports both regular and correction)
        command.add("-cp");
        command.add(".:*");   // Load all jars in directory

        command.add("com.tin.FVU.FVU");

        // Common arguments
        command.add(txtPath);
        command.add(errPath);
        command.add(fvuPath);
        command.add("0");          // Always 0
        command.add(version);

        if (isCorrectionStatement) {

            if (consolidatePath == null || consolidatePath.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "Consolidated (.tds) file is mandatory for correction mode");
            }

            command.add("1");      // Correction flag
            if (csiPath != null && !csiPath.trim().isEmpty()) {
                command.add(csiPath);
                logger.info("Correction Mode WITH CSI: TXT [{}], CSI [{}], TDS [{}]",
                        new File(txtPath).getName(),
                        new File(csiPath).getName(),
                        new File(consolidatePath).getName());
            } else {
                command.add(""); // Blank CSI
                logger.info("Correction Mode WITHOUT CSI: TXT [{}], TDS [{}]",
                        new File(txtPath).getName(),
                        new File(consolidatePath).getName());
            }

            command.add(consolidatePath);

            logger.info("Correction Mode: TXT [{}], TDS [{}]",
                    new File(txtPath).getName(),
                    new File(consolidatePath).getName());

        } else {

            if (csiPath == null || csiPath.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "CSI file is mandatory for regular mode");
            }

            command.add("0");      // Regular flag
            command.add(csiPath);

            logger.info("Regular Mode: TXT [{}], CSI [{}]",
                    new File(txtPath).getName(),
                    new File(csiPath).getName());
        }

        logger.info("Executing Command: {}", command);

        ProcessBuilder pb = new ProcessBuilder(command);

        // VERY IMPORTANT → run inside folder where all jars exist
        pb.directory(new File(jarPath).getParentFile());

        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Capture output
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader =
                         new BufferedReader(
                                 new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("[FVU] {}", line);
                }

            } catch (Exception e) {
                logger.error("Error reading FVU output", e);
            }
        });

        outputReader.start();

		// Watch output folder and kill jar after completion
	
		    startFileWatcher(process, uniqueFolderPath, errPath, fvuPath);
		

		int exitCode = process.waitFor();
		logger.info("Completed JAR call. Exit code: {}", exitCode);
		System.out.println("Completed JAR execution. Exit code: " + exitCode);

		// Wait for output reader to finish
		outputReader.join(5000); // Wait max 5 seconds for output reading to complete
	}

	/**
	 * Monitor output files and terminate process when complete
	 */
	private static void startFileWatcher(Process process, String uniqueFolderPath,
			String errPath, String fvuPath) {

		new Thread(() -> {
			try {
				File folder = new File(uniqueFolderPath);
				File lastPdf = null;
				long lastSize = -1;
				long lastErrHtmlSize = -1;
				long lastFvuSize = -1;
				int stableCount = 0;

				long inactivityTimeout = 10 * 60 * 1000; // 10 minutes
				long startTime = System.currentTimeMillis();
				long lastActivityTime = startTime;

				logger.info("File watcher started for folder: {}", uniqueFolderPath);

				while (process.isAlive()) {
					long now = System.currentTimeMillis();

					// Check for SUCCESS condition - PDF file stabilized
					File[] pdfFiles = folder.listFiles((d, name) -> name.toLowerCase().endsWith(".pdf"));

					if (pdfFiles != null && pdfFiles.length > 0) {
						File candidate = pdfFiles[0];
						long newSize = candidate.length();

						if (lastPdf != null && candidate.equals(lastPdf)) {
							if (newSize == lastSize) {
								stableCount++;
							} else {
								stableCount = 0;
								lastActivityTime = now; // Activity detected
							}
						}

						lastPdf = candidate;
						lastSize = newSize;

						// If PDF size stable for ~10 seconds (5 cycles @ 2s) → SUCCESS
						if (stableCount >= 5) {
							logger.info("PDF stabilized at {} bytes. Validation SUCCESS. Killing gov JAR.", newSize);
							process.destroyForcibly();
							break;
						}
					}

					// Check for FAILURE condition - Error files present
					File errHtmlFile = new File(errPath);
					long errHtmlSize = errHtmlFile.exists() ? errHtmlFile.length() : -1;

					File[] errFiles = folder.listFiles((d, name) -> name.toLowerCase().endsWith(".err"));

					boolean htmlExists = errHtmlFile.exists();
					boolean errFileExists = (errFiles != null && errFiles.length > 0);

					if (htmlExists && errFileExists) {
						logger.info("Error files detected (.html + .err). Validation FAILED. Killing gov JAR.");
						process.destroyForcibly();
						break;
					}

					// Track error HTML activity
					if (errHtmlSize != lastErrHtmlSize && errHtmlSize > 0) {
						lastErrHtmlSize = errHtmlSize;
						lastActivityTime = now;
						logger.debug("Error HTML file activity detected: {} bytes", errHtmlSize);
					}

					// Track FVU file activity
					File fvuFile = new File(fvuPath);
					if (fvuFile.exists()) {
						long fvuSize = fvuFile.length();
						if (fvuSize != lastFvuSize && fvuSize > 0) {
							lastFvuSize = fvuSize;
							lastActivityTime = now;
							logger.debug("FVU file activity detected: {} bytes", fvuSize);
						}
					}

					// TIMEOUT - No activity for 10 minutes
					if (now - lastActivityTime >= inactivityTimeout) {
						logger.warn("No file activity for 10 minutes. TIMEOUT. Killing gov JAR.");
						process.destroyForcibly();
						break;
					}

					Thread.sleep(2000); // Check every 2 seconds
				}

				logger.info("File watcher completed for folder: {}", uniqueFolderPath);

			} catch (Exception e) {
				logger.error("File watcher failed: {}", e.getMessage(), e);
			}
		}).start();
	}
}