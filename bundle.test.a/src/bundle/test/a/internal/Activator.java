package bundle.test.a.internal;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class Activator implements BundleActivator {

	@Override
	public void start(BundleContext context) throws Exception {
		System.out.println(getClass().getCanonicalName() + " from->" + getClass().getModule());
	}

	@Override
	public void stop(BundleContext context) throws Exception {
	}

}
