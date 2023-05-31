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

import java.io.InputStream;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

public class App {
	private static final Logger LOG = Logger.getLogger(App.class.getCanonicalName());
	private static Attributes manifestMainAttributes;

	public static void main(String[] args) {
		if (LOG.isLoggable(Level.INFO))
			LOG.info("Started " + getManifestEntry("AppName") + ", build version " + getManifestEntry("BuildTime"));
		System.out.println("Hello World!");
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
