package com.example.AutomationJavaUtility.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FvuJarRunner {

    public static void runFvuJar(String jarPath, String txtPath, String csiPath,
                                 String outputDir, String errPath, String fvuPath,
                                 String uniqueFolderPath) throws Exception {

    	final Logger logger = LoggerFactory.getLogger(FvuJarRunner.class);
		logger.info("Reached at jar run process.");


        System.out.println("Running FVU JAR: " + jarPath);

        // Original flags and version
        String flag1 = "0";
        String version = "9.2";
        String flag2 = "0";

        // Build command list (cross-platform safe)
        List<String> command = new ArrayList<>();
		/*
		 * command.add("java"); command.add("-jar");
		 */
        //trial so gui not appears after file validations
        command.add("xvfb-run");
        command.add("-a");
        command.add("java");
        command.add("-jar");
        command.add(jarPath);
        command.add(txtPath);
        command.add(errPath);
        command.add(fvuPath);
        command.add(flag1);
        command.add(version);
        command.add(flag2);
        command.add(csiPath);
		logger.info("Executing FVU JAR command: {}", command);

        System.out.println("Executing command: " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(jarPath).getParentFile());
		logger.debug("Unique folder created for outputs: {}", uniqueFolderPath);

        Process process = pb.start();

     // Watch output folder and kill jar after PDF stabilization OR 16 min timeout
        new Thread(() -> {
            try {
                File folder = new File(uniqueFolderPath);
                File lastPdf = null;
                long lastSize = -1;
                int stableCount = 0;

                long startTime = System.currentTimeMillis();
                long timeoutMillis = 16 * 60 * 1000; // 16 minutes
                long stabilizationWindow = 5 * 60 * 1000; // 5 minutes

                while (process.isAlive()) {
                    File[] pdfFiles = folder.listFiles((d, name) -> name.toLowerCase().endsWith(".pdf"));

                    if (pdfFiles != null && pdfFiles.length > 0) {
                        File candidate = pdfFiles[0];
                        long newSize = candidate.length();

                        if (lastPdf != null && candidate.equals(lastPdf)) {
                            if (newSize == lastSize) {
                                stableCount++;
                            } else {
                                stableCount = 0;
                            }
                        }

                        lastPdf = candidate;
                        lastSize = newSize;

                        // If PDF size stable for ~1 sec (5 cycles @ 200ms) AND within 5 mins → kill
                        if (stableCount >= 5 &&
                            (System.currentTimeMillis() - startTime) <= stabilizationWindow) {
                            logger.info("PDF stabilized. Killing gov JAR.");
                            process.destroyForcibly();
                            break;
                        }
                    }

                    // Hard kill after 16 minutes no matter what
                    if (System.currentTimeMillis() - startTime >= timeoutMillis) {
                        logger.warn("Timeout reached (16 min). Killing gov JAR.");
                        process.destroyForcibly();
                        break;
                    }

                    Thread.sleep(2000);
                }
            } catch (Exception e) {
                logger.error("Watcher failed: {}", e.getMessage(), e);
            }
        }).start();


        process.waitFor();
		logger.info("Completed JAR call and processing .");

        System.out.println("Completed JAR execution.");
    }
}
