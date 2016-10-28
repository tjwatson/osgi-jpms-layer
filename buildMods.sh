#!/bin/sh -x

javac -d mods/bundle.test.a bundle.test.a.api/src/module-info.java bundle.test.a.api/src/bundle/test/a/A.java;
javac --module-path mods -d mods/bundle.test.b bundle.test.b.api/src/module-info.java bundle.test.b.api/src/bundle/test/b/B.java;
javac --module-path mods -d mods/jpms.test.a jpms.test.a/src/module-info.java jpms.test.a/src/jpms/test/a/A.java jpms.test.a/src/jpms/test/a/B.java jpms.test.a/src/jpms/test/a/C.java;
javac --module-path mods -d mods/jpms.test.b jpms.test.b/src/module-info.java jpms.test.b/src/jpms/test/b/A.java jpms.test.b/src/jpms/test/b/B.java jpms.test.b/src/jpms/test/b/C.java;
 
jar --create --file=mlib/jpms.test.a.jar --module-version=1.0 -C mods/jpms.test.a .;
jar --create --file=mlib/jpms.test.b.jar --module-version=1.0 -C mods/jpms.test.b .;

