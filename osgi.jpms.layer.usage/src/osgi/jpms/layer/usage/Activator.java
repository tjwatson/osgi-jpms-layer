package osgi.jpms.layer.usage;

import java.io.File;
import java.lang.reflect.Layer;
import java.lang.reflect.Module;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.FrameworkWiring;

import osgi.jpms.layer.LayerFactory;
import osgi.jpms.layer.LayerFactory.NamedLayer;

public class Activator implements BundleActivator {

	@Override
	public void start(BundleContext context) throws Exception {
		try {
			LayerFactory factory = context.getService(context.getServiceReference(LayerFactory.class));
			testBundleLayer(factory, context);
			Collection<Bundle> toRefresh = new ArrayList<>();

			for (Bundle b : context.getBundles()) {
				if (b.getSymbolicName().startsWith("bundle.test.")) {
					toRefresh.add(b);
				}
			}
			// refresh the test bundles and test another layer
			CountDownLatch refreshed = new CountDownLatch(1);
			context.getBundle(Constants.SYSTEM_BUNDLE_LOCATION).adapt(FrameworkWiring.class).refreshBundles(toRefresh, (event) ->{
				refreshed.countDown();
			});
			refreshed.await();

			testBundleLayer(factory, context);
		}
		catch (Throwable t) {
			t.printStackTrace();
		}
		// shutdown framework at end of test
		new Thread(() -> {
			try {
				Thread.sleep(1000);
				context.getBundle(Constants.SYSTEM_BUNDLE_LOCATION).stop();
			} catch (Exception e) {
			}
		}).start();
	}

	private void testBundleLayer(LayerFactory layerFactory, BundleContext context) {
		Set<String> jpmsNames = Set.of("jpms.test.a", "jpms.test.b");
		Set<Path> modPaths = Set.of(new File(context.getProperty("jpms.mods.path")).toPath());
		System.out.println("Using modules at: " + modPaths);

		NamedLayer jpmsLayer = layerFactory.createLayerWithManyLoaders("JPMS Test Layer Many ClassLoaders", modPaths , jpmsNames, null);
		tryLoadClasses(jpmsLayer);

		jpmsLayer = layerFactory.createLayerWithOneLoader("JPMS Test Layer Many ClassLoaders", modPaths , jpmsNames, null);
		tryLoadClasses(jpmsLayer);
	}

	private void tryLoadClasses(NamedLayer jpmsLayer) {
		for (Module jpmsModule : jpmsLayer.getLayer().modules()) {
			System.out.println(jpmsModule.getDescriptor());
			tryLoadClass(jpmsLayer.getLayer(), jpmsModule.getName(), jpmsModule.getName() + ".C");
			tryLoadClass(jpmsLayer.getLayer(), jpmsModule.getName(), jpmsModule.getName() + ".A");
			tryLoadClass(jpmsLayer.getLayer(), jpmsModule.getName(), jpmsModule.getName() + ".B");
		}
	}

	private void tryLoadClass(Layer layer, String moduleName, String className) {
		try {
			Class<?> c = layer.findLoader(moduleName).loadClass(className);
			System.out.println("SUCCESS: " + c + " from->" + c.getModule() + " SUPER: " + c.getSuperclass() + " from->" + c.getSuperclass().getModule());
		} catch (Throwable t) {
			System.err.println("FAILED: " + t.getMessage());
		}
	}

	@Override
	public void stop(BundleContext context) throws Exception {
	}

}
