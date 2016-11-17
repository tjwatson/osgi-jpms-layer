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

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Module;
import java.security.ProtectionDomain;
import java.util.Collection;
import java.util.HashMap;
import java.util.function.BiConsumer;

public class AddReadsUtil {
	private static final String ADD_READS_CLASS_NAME = "osgi.jpms.internal.layer.addreads.AddReadsConsumer";
	private static final String ADD_READS_CLASS_RESOURCE = "/osgi/jpms/internal/layer/addreads/AddReadsConsumer.class";

	private static final Object UNSAFE;
	private static final Method DEFINE_CLASS;
	private static final byte[] BYTES;
	private static final Throwable ERROR;
	static {
		Object unsafe = null;
		Method defineClass = null;
		byte[] bytes = null;
		Throwable error = null;
		try {
			// Use reflection on Unsafe to avoid having to compile against it
			Class<?> unsafeClass = Class.forName("sun.misc.Unsafe"); //$NON-NLS-1$
			Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe"); //$NON-NLS-1$

			// NOTE: deep reflection is allowed on sun.misc package for java 9.
			theUnsafe.setAccessible(true);
			unsafe = theUnsafe.get(null);

			// sun.misc.Unsafe.defineClass(String, byte[], int, int, ClassLoader, ProtectionDomain)
			defineClass = unsafeClass.getMethod("defineClass", String.class, byte[].class, int.class, int.class, ClassLoader.class, ProtectionDomain.class); //$NON-NLS-1$

			bytes = getBytes(AddReadsUtil.class.getResource(ADD_READS_CLASS_RESOURCE).openStream(), -1, 4000);
		} catch (Throwable t) {
			error = t;
		}
		UNSAFE = unsafe;
		DEFINE_CLASS = defineClass;
		BYTES = bytes;
		ERROR= error;
	}

	public static byte[] getBytes(InputStream in, int length, int BUF_SIZE) throws IOException {
		byte[] classbytes;
		int bytesread = 0;
		int readcount;
		try {
			if (length > 0) {
				classbytes = new byte[length];
				for (; bytesread < length; bytesread += readcount) {
					readcount = in.read(classbytes, bytesread, length - bytesread);
					if (readcount <= 0) /* if we didn't read anything */
						break; /* leave the loop */
				}
			} else /* does not know its own length! */ {
				length = BUF_SIZE;
				classbytes = new byte[length];
				readloop: while (true) {
					for (; bytesread < length; bytesread += readcount) {
						readcount = in.read(classbytes, bytesread, length - bytesread);
						if (readcount <= 0) /* if we didn't read anything */
							break readloop; /* leave the loop */
					}
					byte[] oldbytes = classbytes;
					length += BUF_SIZE;
					classbytes = new byte[length];
					System.arraycopy(oldbytes, 0, classbytes, 0, bytesread);
				}
			}
			if (classbytes.length > bytesread) {
				byte[] oldbytes = classbytes;
				classbytes = new byte[bytesread];
				System.arraycopy(oldbytes, 0, classbytes, 0, bytesread);
			}
		} finally {
			try {
				in.close();
			} catch (IOException ee) {
				// nothing to do here
			}
		}
		return classbytes;
	}

	static void checkForError() {
		if (ERROR != null) {
			throw new IllegalStateException("Error initializing addReads.", ERROR);
		}
	}
	
	private static final HashMap<Module, Class<BiConsumer<Module, Module>>> addReadsClasses = new HashMap<>(); 
	@SuppressWarnings("unchecked")
	static void defineAddReadsConsumer(Module module) {
		try {
			Class<?> clazz = (Class<?>) DEFINE_CLASS.invoke(UNSAFE, ADD_READS_CLASS_NAME, BYTES, Integer.valueOf(0), Integer.valueOf(BYTES.length), module.getClassLoader(), AddReadsUtil.class.getProtectionDomain());
			if (!module.equals(clazz.getModule())) {
				throw new RuntimeException("Failed to define AddReadsConsumer class into module: " + module + " : " + clazz.getModule());
			}
			addReadsClasses.put(module, (Class<BiConsumer<Module, Module>>) clazz);
		} catch (Exception e) {
			throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException("Error defining addReads", e);
		}
	}

	static void clearAddReadsCunsumer(Module module) {
		addReadsClasses.remove(module);
	}

	static void addReads(Module wantsRead, Collection<Module> toTargets) {
		try {
			Class<BiConsumer<Module, Module>> addReadsConsumer = addReadsClasses.get(wantsRead);
			BiConsumer<Module, Module> addReads = addReadsConsumer.getConstructor().newInstance();
			for (Module toTarget : toTargets) {
				addReads.accept(wantsRead, toTarget);				
			}
		} catch (Exception e) {
			throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException("Error calling addReads.", e);
		}
	}
}
