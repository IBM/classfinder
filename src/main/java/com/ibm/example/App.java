/*
 * Copyright 2023, 2023 IBM Corp. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ibm.example;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

public class App {
	public static final int RC_FOUND = 0;
	public static final int RC_NOT_FOUND = 2;
	private static final String CLASSNAME = App.class.getCanonicalName();
	private static final Logger LOG = Logger.getLogger(CLASSNAME);
	private static Attributes manifestMainAttributes;

	public static void usage(String error) {
		if (error != null) {
			System.err.println("ERROR: " + error);
		}
		System.err.println("usage: java -jar classfinder.jar CLASS [DIRECTORY...]");
		System.exit(1);
	}

	public static void main(String[] args) {
		if (LOG.isLoggable(Level.INFO))
			LOG.info("Started " + getManifestEntry("AppName") + ", version " + getManifestEntry("AppVersion")
					+ ", build version " + getManifestEntry("BuildTime"));

		boolean keepExtractedFiles = false;
		String findClass = null;
		List<File> directories = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg.startsWith("-")) {
				if (arg.equals("-h") || arg.equals("--help") || arg.equals("--usage")) {
					usage(null);
				} else if (arg.equals("-k") || arg.equals("--keep-extracted-files")) {
					keepExtractedFiles = true;
				}
			} else {
				if (findClass == null) {
					findClass = arg;
				} else {
					directories.add(new File(arg));
				}
			}
		}

		if (findClass == null) {
			usage("CLASS not specified.");
		}

		if (directories.size() == 0) {
			directories.add(new File("."));
		}

		if (LOG.isLoggable(Level.INFO))
			LOG.info("Searching for " + findClass + " in " + directories);

		Set<String> extractedDirectories = new HashSet<>();

		int rc = search(findClass, directories, extractedDirectories, (file) -> {
			System.out.println(getNormalizedPath(file));
		});

		if (!keepExtractedFiles) {
			if (LOG.isLoggable(Level.FINE))
				LOG.fine("Started deleting extracted directories");

			for (String extractedDirectory : extractedDirectories) {
				try {
					if (LOG.isLoggable(Level.FINE))
						LOG.fine("Started deleting extracted directory " + extractedDirectory);

					FileUtils.deleteDirectory(new File(extractedDirectory));

					if (LOG.isLoggable(Level.FINE))
						LOG.fine("Finished deleting extracted directory " + extractedDirectory);
				} catch (IOException e) {
					if (LOG.isLoggable(Level.WARNING))
						LOG.log(Level.WARNING, "Error cleaning up extracted directory " + extractedDirectory, e);
				}
			}

			if (LOG.isLoggable(Level.FINE))
				LOG.fine("Finished deleting extracted directories");
		}

		if (LOG.isLoggable(Level.INFO))
			LOG.info("Exiting with return code " + rc);

		System.exit(rc);
	}

	public static String getNormalizedPath(File file) {
		try {
			return Paths.get(file.getAbsolutePath()).toRealPath().toString();
		} catch (IOException e) {
			return file.getAbsolutePath();
		}
	}

	public static int search(String findClass, List<File> directories, Set<String> extractedDirectories,
			Consumer<File> foundMatch) {
		int rc = RC_NOT_FOUND;

		Set<String> directoriesSearched = new HashSet<>();
		Stack<File> directoriesToSearch = new Stack<>();
		for (File directory : directories) {
			directoriesToSearch.push(directory);
		}

		while (!directoriesToSearch.isEmpty()) {
			File directory = directoriesToSearch.pop();
			if (searchDirectory(findClass, directory, extractedDirectories, foundMatch, directoriesToSearch,
					directoriesSearched)) {
				rc = RC_FOUND;
			}
		}
		return rc;
	}

	private static boolean searchDirectory(String findClass, File directory, Set<String> extractedDirectories,
			Consumer<File> foundMatch, Stack<File> directoriesToSearch, Set<String> directoriesSearched) {
		
		if (LOG.isLoggable(Level.FINER))
			LOG.entering(CLASSNAME, "search", new Object[] { findClass, directory.getAbsolutePath() });
		
		boolean result = false;

		String normalizedPath = getNormalizedPath(directory);
		
		if (!directoriesSearched.contains(normalizedPath)) {
			directoriesSearched.add(normalizedPath);

			for (File directoryEntry : directory.listFiles()) {
				if (directoryEntry.isDirectory()) {
					if (searchDirectory(findClass, directoryEntry, extractedDirectories, foundMatch, directoriesToSearch,
							directoriesSearched)) {
						result = true;
					}
				} else {
					if (LOG.isLoggable(Level.FINE))
						LOG.fine("Analyzing " + directoryEntry);

					if (isKnownCompressedFile(directoryEntry)) {
						try {
							directoriesToSearch.push(extractFile(directoryEntry, extractedDirectories));
						} catch (IOException e) {
							if (LOG.isLoggable(Level.WARNING))
								LOG.log(Level.WARNING, "Error extracting file " + directoryEntry.getAbsolutePath(), e);
						}
					} else if (doesJavaClassNameMatch(findClass, directoryEntry)) {
						foundMatch.accept(directoryEntry);
					}
				}
			}
		}

		if (LOG.isLoggable(Level.FINER))
			LOG.exiting(CLASSNAME, "search", result);

		return result;
	}

	public static boolean isKnownCompressedFile(File file) {
		String fileName = file.getName().toLowerCase();
		return isZipFile(fileName);
	}

	public static File extractFile(File file, Set<String> extractedDirectories) throws IOException {
		String fileName = file.getName().toLowerCase();

		// First create the extracted directory
		File extractDestination = new File(file.getParentFile(), file.getName() + "_unpack");
		Path extractDestinationPath = extractDestination.toPath();
		if (!extractDestination.exists()) {

			if (LOG.isLoggable(Level.FINE))
				LOG.fine("Creating extract destination " + extractDestination.getAbsolutePath());

			extractDestination.mkdir();
		}
		extractedDirectories.add(getNormalizedPath(extractDestination));

		if (LOG.isLoggable(Level.FINE))
			LOG.fine("Started extracting file " + file.getName() + " to " + extractDestination.getAbsolutePath());

		if (isZipFile(fileName)) {
			try (ZipFile zipFile = new ZipFile(file, ZipFile.OPEN_READ)) {
				Enumeration<? extends ZipEntry> entries = zipFile.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					Path entryPath = extractDestinationPath.resolve(entry.getName());
					if (entryPath.normalize().startsWith(extractDestinationPath.normalize())) {
						if (entry.isDirectory()) {
							Files.createDirectories(entryPath);
						} else {
							Files.createDirectories(entryPath.getParent());
							try (InputStream in = zipFile.getInputStream(entry)) {
								try (OutputStream out = new FileOutputStream(entryPath.toFile())) {
									IOUtils.copy(in, out);
								}
							}
						}
					}
				}
			}
		} else {
			throw new UnsupportedOperationException("Unknown compressed file name " + file.getAbsolutePath());
		}

		if (LOG.isLoggable(Level.FINE))
			LOG.fine("Finished extracting file " + file.getName() + " to " + extractDestination.getAbsolutePath());

		return extractDestination;
	}

	public static boolean isZipFile(String fileName) {
		return fileName.endsWith(".jmod") || fileName.endsWith(".zip") || fileName.endsWith(".jar")
				|| fileName.endsWith(".hcd");
	}

	public static boolean doesJavaClassNameMatch(String className, File path) {
		boolean result = false;

		if (LOG.isLoggable(Level.FINER))
			LOG.entering(CLASSNAME, "doesJavaClassNameMatch", new Object[] { className, path, path.getName() });

		if ((className + ".class").equals(path.getName())) {
			result = true;
		}

		if (LOG.isLoggable(Level.FINER))
			LOG.exiting(CLASSNAME, "doesJavaClassNameMatch", result);

		return result;
	}

	public static synchronized String getManifestEntry(String name) {
		if (manifestMainAttributes == null) {
			try (InputStream inputStream = App.class.getClassLoader().getResourceAsStream("META-INF/MANIFEST.MF")) {
				Manifest manifest = new Manifest(inputStream);
				manifestMainAttributes = manifest.getMainAttributes();
			} catch (Throwable t) {
				if (LOG.isLoggable(Level.FINE))
					LOG.log(Level.FINE, "Could not read manifest entry " + name, t);
			}
		}
		if (manifestMainAttributes != null) {
			return manifestMainAttributes.getValue(name);
		} else {
			return "Unknown manifest value for " + name;
		}
	}
}
