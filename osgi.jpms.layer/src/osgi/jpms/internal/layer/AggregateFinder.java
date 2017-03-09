package osgi.jpms.internal.layer;

import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class AggregateFinder implements ModuleFinder {
	private final Map<String, ModuleFinder> finders;
	
	public AggregateFinder(Map<String, ModuleFinder> finders) {
		this.finders = finders;
	}

	@Override
	public Optional<ModuleReference> find(String name) {
		ModuleFinder finder = finders.get(name);
		return finder == null ? Optional.empty() : finder.find(name);
	}

	@Override
	public Set<ModuleReference> findAll() {
		Set<ModuleReference> result = new HashSet<>();
		for (ModuleFinder finder : finders.values()) {
			result.addAll(finder.findAll());
		}
		return result;
	}

}
