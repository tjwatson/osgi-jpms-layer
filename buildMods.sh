#!/bin/sh -x

javac -d mods/bundle.test.a.callable bundle.test.a.callable/src/module-info.java bundle.test.a.callable/src/bundle/test/a/callable/ACallable.java;
javac --module-path mods -d mods/bundle.test.a bundle.test.a/src/module-info.java bundle.test.a/src/bundle/test/a/A.java bundle.test.a/src/bundle/test/a/ACallableFactory.java bundle.test.a/src/bundle/test/a/internal/Activator.java;
javac --module-path mods -d mods/bundle.test.b bundle.test.b/src/module-info.java bundle.test.b/src/bundle/test/b/B.java bundle.test.b/src/bundle/test/b/internal/Activator.java;
javac --module-path mods -d mods/jpms.test.a jpms.test.a/src/module-info.java jpms.test.a/src/jpms/test/a/A.java jpms.test.a/src/jpms/test/a/B.java jpms.test.a/src/jpms/test/a/C.java jpms.test.a/src/jpms/test/a/TestFunction.java  jpms.test.a/src/jpms/test/a/UseACallableFactory.java;
javac --module-path mods -d mods/jpms.test.b jpms.test.b/src/module-info.java jpms.test.b/src/jpms/test/b/A.java jpms.test.b/src/jpms/test/b/B.java jpms.test.b/src/jpms/test/b/C.java jpms.test.b/src/jpms/test/b/TestFunction.java jpms.test.b/src/jpms/test/b/UseACallableFactory.java;
 

jar --create --file=mlib/bundle.test.a.callable.jar --module-version=1.0 -m bundle.test.a.callable/META-INF/MANIFEST.MF -C mods/bundle.test.a.callable .;
jar --create --file=mlib/bundle.test.a.jar --module-version=1.0 -m bundle.test.a/META-INF/MANIFEST.MF -C mods/bundle.test.a . -C bundle.test.a component.xml;
jar --create --file=mlib/bundle.test.b.jar --module-version=1.0 -m bundle.test.b/META-INF/MANIFEST.MF -C mods/bundle.test.b . -C bundle.test.b component.xml;
jar --create --file=mlib/jpms.test.a.jar --module-version=1.0 -C mods/jpms.test.a .;
jar --create --file=mlib/jpms.test.b.jar --module-version=1.0 -C mods/jpms.test.b .;

