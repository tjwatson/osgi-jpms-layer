/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package osgi.jpms.layer;

import java.io.File;
import java.lang.reflect.Layer;
import java.lang.reflect.Module;
import java.util.Set;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;

public class Activator implements BundleActivator {

	@Override
	public void start(BundleContext context) throws Exception {
		try {
			testBundleLayer(context);
		}
		catch (Throwable t) {
			t.printStackTrace();
		}
		// shutdown framework at end of test
		context.getBundle(Constants.SYSTEM_BUNDLE_LOCATION).stop();
	}

	private void testBundleLayer(BundleContext context) {
		// Create a bundle layer with a subset of the installed bundles
		Set<String> bsns = Set.of("bundle.test.a", "bundle.test.b", Constants.SYSTEM_BUNDLE_SYMBOLICNAME);
		Layer bundleLayer = LayerFactory.createBundleLayer(context, bsns);
		for (Module bundleModule : bundleLayer.modules()) {
			System.out.println(bundleModule.getDescriptor());
		}

		// Create a jpms Layer using the bundle layer as a parent
		Set<String> jpmsNames = Set.of("jpms.test.a", "jpms.test.b");
		Layer jpmsLayer = LayerFactory.createJpmsLayer(bundleLayer, new File(context.getProperty("jpms.mods.path")).toPath(), jpmsNames);
		for (Module jpmsModule : jpmsLayer.modules()) {
			System.out.println(jpmsModule.getDescriptor());
			tryLoadClass(jpmsLayer, jpmsModule.getName(), jpmsModule.getName() + ".C");
			tryLoadClass(jpmsLayer, jpmsModule.getName(), jpmsModule.getName() + ".A");
			tryLoadClass(jpmsLayer, jpmsModule.getName(), jpmsModule.getName() + ".B");
		}
	}

	private void tryLoadClass(Layer layer, String moduleName, String className) {
		try {
			Class c = layer.findLoader(moduleName).loadClass(className);
			System.out.println("SUCCESS: " + c + " from->" + c.getModule());
		} catch (Throwable t) {
			System.out.println("FAILED: " + t.getMessage());
		}
	}


	@Override
	public void stop(BundleContext context) throws Exception {

	}

}
