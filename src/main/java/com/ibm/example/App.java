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

import org.apache.bcel.classfile.AnnotationEntry;
import org.apache.bcel.classfile.ClassFormatException;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Field;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

public class App {
	public static final int RC_FOUND = 0;
	public static final int RC_NOT_FOUND = 2;

	static {
		if (System.getProperty("java.util.logging.SimpleFormatter.format") == null) {
			System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tc] %4$s: %5$s %6$s%n");
		}
	}

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

		boolean skipMACOSXdirectories = true;
		boolean keepExtractedFiles = false;
		String findClass = null;
		List<File> directoriesOrFiles = new ArrayList<>();
		List<AnnotationSearch> annotationSearches = new ArrayList<>();
		
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg.startsWith("-")) {
				if (arg.equals("-h") || arg.equals("--help") || arg.equals("--usage")) {
					usage(null);
				} else if (arg.equals("-k") || arg.equals("--keep-extracted-files")) {
					keepExtractedFiles = true;
				} else if (arg.equals("-a") || arg.equals("--annotation-search")) {
					AnnotationSearch annotationSearch = new AnnotationSearch();
					annotationSearches.add(annotationSearch);
					String spec = args[++i];
					String[] pieces = spec.split(";");
					annotationSearch.annotation = pieces[0];
					if (pieces.length > 1) {
						annotationSearch.toStringSearch = pieces[1];
					}
				} else {
					throw new RuntimeException("Unknown option " + arg);
				}
			} else {
				if (findClass == null) {
					findClass = arg;
				} else {
					directoriesOrFiles.add(new File(arg));
				}
			}
		}

		if (findClass == null) {
			usage("CLASS not specified.");
		}

		if (directoriesOrFiles.size() == 0) {
			directoriesOrFiles.add(new File("."));
		}
		
		if (LOG.isLoggable(Level.INFO))
			LOG.info("Searching for " + findClass + " in " + directoriesOrFiles);

		Set<String> extractedDirectories = new HashSet<>();

		int rc = search(findClass, directoriesOrFiles, extractedDirectories, annotationSearches, (file) -> {
			System.out.println(getNormalizedPath(file));
		}, skipMACOSXdirectories);

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
			LOG.info("Exiting with return code " + rc + " (" + getHumanReadableReturnCode(rc) + ")");

		System.exit(rc);
	}
	
	public static String getHumanReadableReturnCode(int rc) {
		switch (rc) {
		case RC_FOUND:
			return "at least one result found";
		case RC_NOT_FOUND:
			return "no results found";
		default:
			return "unknown";
		}
	}

	public static String getNormalizedPath(File file) {
		try {
			return Paths.get(file.getAbsolutePath()).toRealPath().toString();
		} catch (IOException e) {
			return file.getAbsolutePath();
		}
	}

	public static int search(String findClass, List<File> directories, Set<String> extractedDirectories, List<AnnotationSearch> annotationSearches,
			Consumer<File> foundMatch, boolean skipMACOSXdirectories) {
		int rc = RC_NOT_FOUND;

		Set<String> directoriesSearched = new HashSet<>();
		Stack<File> directoriesToSearch = new Stack<>();
		for (File directory : directories) {
			directoriesToSearch.push(directory);
		}

		while (!directoriesToSearch.isEmpty()) {
			File directory = directoriesToSearch.pop();
			if (searchDirectory(findClass, directory, extractedDirectories, annotationSearches, foundMatch, directoriesToSearch,
					directoriesSearched, skipMACOSXdirectories)) {
				rc = RC_FOUND;
			}
		}
		return rc;
	}

	private static boolean searchDirectory(String findClass, File directory, Set<String> extractedDirectories, List<AnnotationSearch> annotationSearches,
			Consumer<File> foundMatch, Stack<File> directoriesToSearch, Set<String> directoriesSearched,
			boolean skipMACOSXdirectories) {

		if (LOG.isLoggable(Level.FINER))
			LOG.entering(CLASSNAME, "search", new Object[] { findClass, directory.getAbsolutePath() });

		boolean result = false;

		String normalizedPath = getNormalizedPath(directory);

		if (!directoriesSearched.contains(normalizedPath)) {
			directoriesSearched.add(normalizedPath);

			if (!shouldSkipDirectory(directory, normalizedPath, skipMACOSXdirectories)) {
				File[] searchFiles = directory.isFile() ? new File[] { directory } : directory.listFiles();
				for (File directoryEntry : searchFiles) {
					if (directoryEntry.isDirectory()) {
						if (searchDirectory(findClass, directoryEntry, extractedDirectories, annotationSearches, foundMatch,
								directoriesToSearch, directoriesSearched, skipMACOSXdirectories)) {
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
									LOG.log(Level.WARNING, "Error extracting file " + directoryEntry.getAbsolutePath(),
											e);
							}
						} else if (isMatch(findClass, directoryEntry, annotationSearches)) {
							foundMatch.accept(directoryEntry);
							result = true;
						}
					}
				}
			} else {
				if (LOG.isLoggable(Level.FINE))
					LOG.fine("Skipping directory");
			}
		}

		if (LOG.isLoggable(Level.FINER))
			LOG.exiting(CLASSNAME, "search", result);

		return result;
	}

	private static boolean shouldSkipDirectory(File directory, String normalizedPath, boolean skipMACOSXdirectories) {
		if (directory.isFile()) {
			directory = directory.getParentFile();
		}
		if (skipMACOSXdirectories && normalizedPath.contains("/__MACOSX/")) {
			return true;
		}
		return false;
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
		fileName = fileName.toLowerCase();
		return fileName.endsWith(".jmod") || fileName.endsWith(".zip") || fileName.endsWith(".jar")
				|| fileName.endsWith(".hcd") || fileName.endsWith(".ear") || fileName.endsWith(".war");
	}

	public static boolean isMatch(String className, File path, List<AnnotationSearch> annotationSearches) {
		boolean result = false;

		if (LOG.isLoggable(Level.FINER))
			LOG.entering(CLASSNAME, "isMatch", new Object[] { className, path, path.getName() });

		String pathName = path.getName();
		
		if ("*".equals(className) && pathName.endsWith(".class")) {
			result = true;
		} else if ((className + ".class").equals(pathName)) {
			result = true;
		}
		
		if (result) {
			if (annotationSearches.size() > 0) {
				
				// Since annotationSearches is specified, then we first set the result
				// back to false and then try to find a match for the searches
				result = false;
				
				if (LOG.isLoggable(Level.FINER))
					LOG.fine("Processing class searches for " + path.getAbsolutePath());

				ClassParser classParser = new ClassParser(path.getAbsolutePath());
				try {
					JavaClass clazz = classParser.parse();
					for (AnnotationSearch annotationSearch : annotationSearches) {
						if (annotationSearch.annotation != null) {
							for (AnnotationEntry annotation : clazz.getAnnotationEntries()) {
								if (searchAnnotation(annotationSearch, pathName, annotation)) {
									result = true;
									break;
								}
							}
						}
						for (Field field : clazz.getFields()) {
							for (AnnotationEntry annotation : field.getAnnotationEntries()) {
								if (searchAnnotation(annotationSearch, pathName, annotation)) {
									result = true;
									break;
								}
							}
						}
						for (Method method : clazz.getMethods()) {
							for (AnnotationEntry annotation : method.getAnnotationEntries()) {
								if (searchAnnotation(annotationSearch, pathName, annotation)) {
									result = true;
									break;
								}
							}
						}
					}
				} catch (ClassFormatException | IOException e) {
					if (LOG.isLoggable(Level.WARNING))
						LOG.log(Level.WARNING, "Could not parse class " + path.getAbsolutePath(), e);
				}
			}
		}

		if (LOG.isLoggable(Level.FINER))
			LOG.exiting(CLASSNAME, "isMatch", result);

		return result;
	}

	private static boolean searchAnnotation(AnnotationSearch annotationSearch, String pathName, AnnotationEntry annotation) {
		// Ljakarta/servlet/annotation/WebServlet;
		String annotationClass = annotation.getAnnotationType();
		annotationClass = annotationClass.substring(1);
		annotationClass = annotationClass.substring(0, annotationClass.length() - 1);
		
		// Example usage:
		// -a "org/eclipse/mat/query/annotations/Argument;isMandatory=true"
		if (annotationClass.equals(annotationSearch.annotation)) {
			if (annotationSearch.toStringSearch == null) {
				return true;
			} else {
				return annotation.toString().contains(annotationSearch.toStringSearch);
			}
		}
		return false;
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
	
	static class AnnotationSearch {
		String annotation;
		String toStringSearch;
	}
}
