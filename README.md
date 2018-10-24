# NOTICE
This is my first attempt at an OSGi - JPMS inter-op.  To provide full OSGi support it ends up breaking lots of JPMS rules which force it to use a one-to-one mapping between Bundle and ModuleLayer.  This allows full control over the wiring of the modules to follow all the advanced rules of OSGi.  Unfortunately this largely negates any advantage to moving to the JPMS module system.  For example, you cannot create jlink images since all layers must be dynamically created at runtime.  It also suffers from performance concerns since resolving and creating all these ModuleLayers is not cheap.  This approach does allow additional module layers to be created on top for "real" Java modules but in the end I think this is the wrong approach.  Since then I have created a new project called Atomos (https://github.com/tjwatson/atomos) which plays more by the rules of JPMS and allows for things like creating a jlink image from a set of OSGi bundles.

# osgi-jpms-layer
Builds a jpms OSGi Bundle Layer which can have child Layers that depend on OSGi Bundles as jpms Modules

# Development Requirements

This project uses Eclipse Neon release or later for development.  In order to compile and run the code you need an Eclipse Neon release with the Java 9 beta support [1].  You will also need the most recent Java 9 open jdk build.

Also needed is a patched equinox launcher project (org.eclipse.equinox.launcher) found in my fork of rt.equinox.framework at github in the tjwatson/jpms branch [2].


[1] https://marketplace.eclipse.org/content/java-9-support-beta-neon

[2] https://github.com/tjwatson/rt.equinox.framework/tree/tjwatson/jpms

# Setup

Configure a Java 9 jdk installation with eclipse and set it as your default VM.

Import all eclipse projects found in the osgi-jpms-layer repository.  Also load only the bundles/org.eclipse.equinox.launcher project from the rt.equinox.framework repository.  This should enable an OSGi Framework launcher called OSGiLayer under the Run Configurations wizard.  If you run this you will see output from a bundle (osgi.jpms.layer.usage) which creates a new layer for jpms modules which has a bundle layer as its parent.  The modules in the jpms layer require bundles from the bundle layer.
