# osgi-jpms-layer
Builds a jpms OSGi Bundle Layer which can have child Layers that depend on OSGi Bundles as jpms Modules

# Development Requirements

This project uses Eclipse Neon release or later for developement.  In order to compile and run the code you need an Eclipes Neon release with the Java 9 beta support [1].  You will also need the most recent Java 9 open jdk build.

Also needed is a patched equinox launcher project (org.eclipse.equinox.launcher) found in my fork of rt.equinox.framework at github in the tjwatson/jpms branch [2].


[1] https://marketplace.eclipse.org/content/java-9-support-beta-neon
[2] https://github.com/tjwatson/rt.equinox.framework/tree/tjwatson/jpms

# Setup

Configure a Java 9 jdk installation with eclipse and set it as your default VM.
Import all eclipse projects found in the osgi-jpms-layer repository.  Also load only the bundles/org.eclipse.equinox.launcher project from the rt.equinox.framework repository.  This should enable an OSGi Framework launcher called OSGiLayer under under the Run Configurations wizard.  If you run this you will see output from a bundle (osgi.jpms.layer.usage) which creates a new layer for jpms modules which has a bundle layer as its parent.  The modules in the jpms layer require bundles from the bundle layer.
