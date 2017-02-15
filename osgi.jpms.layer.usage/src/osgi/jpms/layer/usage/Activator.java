package osgi.jpms.layer.usage;

import java.io.File;
import java.lang.reflect.Layer;
import java.lang.reflect.Module;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

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

			Bundle aTest = context.installBundle("reference:file:" + context.getProperty("jpms.mods.path") + "/" + "jpms.test.a.jar");
			aTest.start();
			Module aModule = factory.getModules().get(aTest);
			System.out.println("JPMS Test module as a bundle.");
			tryUseFunctions(aModule.getLayer());
			aTest.uninstall();
		} catch (Throwable t) {
			t.printStackTrace();
		}

		// shutdown framework at end of test
		new Thread(() -> {
			try {
				Thread.sleep(1000);
				//context.getBundle(Constants.SYSTEM_BUNDLE_LOCATION).stop();
			} catch (Exception e) {
			}
		}).start();
	}

	private void testBundleLayer(LayerFactory layerFactory, BundleContext context) {
		Set<String> jpmsNames = Set.of("jpms.test.a", "jpms.test.b");
		Set<Path> modPaths = Set.of(new File(context.getProperty("jpms.mods.path")).toPath());
		System.out.println("Using modules at: " + modPaths);

		NamedLayer layer1 = layerFactory.createLayerWithManyLoaders("JPMS Test Layer MANY loaders", modPaths , jpmsNames, null);
		tryUseFunctions(layer1);
		layer1.consumeEvents((e) -> System.out.println(e + ": " + layer1.getName() + ": " + layer1.getId()));
		layer1.consumeEvents((e) -> System.out.println(e + ": second consumer"));

		NamedLayer layer2 = layerFactory.createLayerWithOneLoader("JPMS Test Layer ONE loader", modPaths , jpmsNames, null);
		tryUseFunctions(layer2);
		layer2.consumeEvents((e) -> System.out.println(e + ": " + layer2.getName() + ": " + layer2.getId()));
		layer2.consumeEvents((e) -> System.out.println(e + ": second consumer"));
	}

	private void tryUseFunctions(NamedLayer namedLayer) {
		System.out.println(namedLayer.getName());
		System.out.println(namedLayer.getLayer().modules());
		tryUseFunctions(namedLayer.getLayer());
	}

	@SuppressWarnings("unchecked")
	private void tryUseFunctions(Layer layer) {
		@SuppressWarnings("rawtypes")
		ServiceLoader<Function> functions = ServiceLoader.load(layer, Function.class);
		for (Function<String, Callable<String>> function : functions) {
			Module m = function.getClass().getModule();
			if (layer.equals(m.getLayer())) {
				String p = m.getName();
				for (String className: List.of(p + ".C", p + ".A", p + ".B", p + ".UseACallableFactory")) {
					try {
						System.out.println("    SUCCESS: " + function.apply(className).call());
					} catch (Throwable t) {
						System.err.println("    FAILED: " + t.getMessage());
					}
				}
			}
		}
	}

	private void tryLoadClasses(Layer layer) {
		for (Module jpmsModule : layer.modules()) {
			System.out.println("  " + jpmsModule.getDescriptor());
			tryLoadClass(layer, jpmsModule.getName(), jpmsModule.getName() + ".C");
			tryLoadClass(layer, jpmsModule.getName(), jpmsModule.getName() + ".A");
			tryLoadClass(layer, jpmsModule.getName(), jpmsModule.getName() + ".B");
			tryUseFactory(layer, jpmsModule.getName(), jpmsModule.getName() + ".UseACallableFactory");
		}
	}

	private void tryUseFactory(Layer layer, String moduleName, String className) {
		try {
			@SuppressWarnings("unchecked")
			Class<Callable<String>> c = (Class<Callable<String>>) layer.findLoader(moduleName).loadClass(className);
			System.out.println("    SUCCESS: " + c + " from->" + c.getModule() + " SUPER: " + c.getSuperclass() + " from->" + c.getSuperclass().getModule());
			Callable<String> callable = c.getConstructor().newInstance();
			System.out.println("    SUCCESS: " + callable.call());
		} catch (Throwable t) {
			System.err.println("    FAILED: " + t.getMessage());
		}
	}

	private void tryLoadClass(Layer layer, String moduleName, String className) {
		try {
			Class<?> c = layer.findLoader(moduleName).loadClass(className);
			System.out.println("    SUCCESS: " + c + " from->" + c.getModule() + " SUPER: " + c.getSuperclass() + " from->" + c.getSuperclass().getModule());
			System.out.println("    SUCCESS: " + c.getConstructor().newInstance());
		} catch (Throwable t) {
			System.err.println("    FAILED: " + t.getMessage());
		}
	}

	@Override
	public void stop(BundleContext context) throws Exception {
	}

}
