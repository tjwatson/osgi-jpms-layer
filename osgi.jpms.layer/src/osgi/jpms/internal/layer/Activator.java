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
package osgi.jpms.internal.layer;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClassListener;

import osgi.jpms.layer.LayerFactory;

public class Activator implements BundleActivator {
	private ServiceRegistration<?> factoryReg;
	private LayerFactoryImpl factory;
	@Override
	public void start(BundleContext context) {
		// Check that the launcher created a layer for the framework
		if (!Constants.SYSTEM_BUNDLE_SYMBOLICNAME.equals(getClass().getModule().getName())) {
			throw new IllegalStateException("The framework launcher has not setup the system.bundle module for the framework implementation: " + getClass().getModule());
		}
		// Create the LayerFactory implementation and register it
		factory = new LayerFactoryImpl(context);
		// The factory is a bundle listener to keep track of resolved bundles
		context.addBundleListener(factory);
		// The factory is also a WovenClassListener to intercept bundle class loaders before
		// they define any classes.  This is to ensure they are part of a layer before the
		// first class is defined.
		String[] serviceClasses = new String[] {LayerFactory.class.getName(), WovenClassListener.class.getName(), WeavingHook.class.getName()};
		factoryReg = context.registerService(serviceClasses, factory, null);
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		if (factoryReg != null) {
			factoryReg.unregister();
			context.removeBundleListener(factory);
		}
	}

}
