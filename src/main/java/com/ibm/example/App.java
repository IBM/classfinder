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
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

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

		String findClass = null;
		List<File> directories = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg.startsWith("-")) {
				if (arg.equals("-h") || arg.equals("--help") || arg.equals("--usage")) {
					usage(null);
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

		int rc = search(findClass, directories, (file) -> {
			System.out.println(getNormalizedPath(file));
		});

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

	public static int search(String findClass, List<File> directories, Consumer<File> foundMatch) {
		int rc = RC_NOT_FOUND;

		for (File directory : directories) {
			if (search(findClass, directory, foundMatch)) {
				rc = RC_FOUND;
			}
		}
		return rc;
	}

	public static boolean search(String findClass, File directory, Consumer<File> foundMatch) {
		boolean result = false;
		for (File directoryEntry : directory.listFiles()) {
			if (directoryEntry.isDirectory()) {
				if (search(findClass, directoryEntry, foundMatch)) {
					result = true;
				}
			} else {
				if (LOG.isLoggable(Level.FINE))
					LOG.fine("Analyzing " + directoryEntry);

				if (doesJavaClassNameMatch(findClass, directoryEntry)) {
					foundMatch.accept(directoryEntry);
				}
			}
		}
		return result;
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
