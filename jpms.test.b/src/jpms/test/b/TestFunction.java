package jpms.test.b;

import java.util.concurrent.Callable;
import java.util.function.Function;

public class TestFunction implements Function<String, Callable<String>> {

	@SuppressWarnings("unchecked")
	@Override
	public Callable<String> apply(String clazz) {
		try {
			Class<?> c = Class.forName(clazz);
			System.out.println("    SUCCESS: " + c + " from->" + c.getModule() + " SUPER: " + c.getSuperclass() + " from->" + c.getSuperclass().getModule());
			Object obj = c.getConstructor().newInstance();
			if (obj instanceof Callable) {
				return (Callable<String>) obj;
			}
			return () -> obj.toString();
		} catch (Exception e) {
			e.printStackTrace();
		}
		throw new RuntimeException("FAILED: " + clazz);
	}

}
