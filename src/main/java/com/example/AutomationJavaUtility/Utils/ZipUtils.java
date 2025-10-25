package com.example.AutomationJavaUtility.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipUtils {

	public static File zipFolder(File sourceFolder, String zipFilePath) throws IOException {
		File zipFile = new File(zipFilePath);
		try (FileOutputStream fos = new FileOutputStream(zipFile); ZipOutputStream zos = new ZipOutputStream(fos)) {

			zipFiles(sourceFolder, sourceFolder.getName(), zos, zipFile);
		}
		return zipFile;
	}

	private static void zipFiles(File fileToZip, String fileName, ZipOutputStream zos, File zipFile)
			throws IOException {
		if (fileToZip.isHidden()) {
			return;
		}
		// 🚫 Skip the zip file itself
		if (fileToZip.equals(zipFile)) {
			return;
		}
		if (fileToZip.isDirectory()) {
			if (!fileName.endsWith("/")) {
				fileName += "/";
			}
			zos.putNextEntry(new ZipEntry(fileName));
			zos.closeEntry();
			File[] children = fileToZip.listFiles();
			if (children != null) {
				for (File childFile : children) {
					zipFiles(childFile, fileName + childFile.getName(), zos, zipFile);
				}
			}
			return;
		}
		try (FileInputStream fis = new FileInputStream(fileToZip)) {
			zos.putNextEntry(new ZipEntry(fileName));
			byte[] bytes = new byte[1024];
			int length;
			while ((length = fis.read(bytes)) >= 0) {
				zos.write(bytes, 0, length);
			}
		}
	}

}
