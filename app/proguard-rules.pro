# =============================================================================
# ProGuard Configuration File
# -----------------------------------------------------------------------------
# Controls how R8/ProGuard optimizes, obfuscates, and preserves classes in release builds.
# This configuration ensures compatibility with specific libraries used in the app.
# =============================================================================


# ---------------------------------------------------------------------------
# APACHE COMMONS COMPRESS
# ---------------------------------------------------------------------------
# Keep all classes and methods in the Apache Commons Compress library.
# Prevents class/method obfuscation for proper archive handling (ZIP, TAR, etc.).
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-keepattributes Signature
-keep class org.apache.commons.compress.archivers.zip.** { *; }


# ---------------------------------------------------------------------------
# MOZILLA RHINO (JavaScript Engine)
# ---------------------------------------------------------------------------
# Keep Rhino scripting engine classes.
# Required for runtime JavaScript evaluation and execution.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**


# ---------------------------------------------------------------------------
# JAVA BEANS PACKAGE (Reflection-based Metadata)
# ---------------------------------------------------------------------------
# Suppress warnings for Java Beans classes not needed in Android runtime.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor


# ---------------------------------------------------------------------------
# JAVAX SCRIPT (Nashorn/JSR-223)
# ---------------------------------------------------------------------------
# Ignore missing javax.script classes since Android doesn't ship them.
# Some libraries may reference these APIs reflectively.
-dontwarn javax.script.AbstractScriptEngine
-dontwarn javax.script.Bindings
-dontwarn javax.script.Compilable
-dontwarn javax.script.CompiledScript
-dontwarn javax.script.Invocable
-dontwarn javax.script.ScriptContext
-dontwarn javax.script.ScriptEngine
-dontwarn javax.script.ScriptEngineFactory
-dontwarn javax.script.ScriptException
-dontwarn javax.script.SimpleBindings


# ---------------------------------------------------------------------------
# JDK DYNA-LINK (Used by Rhino/Nashorn internally)
# ---------------------------------------------------------------------------
# Prevent warnings for missing JDK Dynalink classes (not available on Android).
-dontwarn jdk.dynalink.CallSiteDescriptor
-dontwarn jdk.dynalink.DynamicLinker
-dontwarn jdk.dynalink.DynamicLinkerFactory
-dontwarn jdk.dynalink.NamedOperation
-dontwarn jdk.dynalink.Namespace
-dontwarn jdk.dynalink.NamespaceOperation
-dontwarn jdk.dynalink.Operation
-dontwarn jdk.dynalink.RelinkableCallSite
-dontwarn jdk.dynalink.StandardNamespace
-dontwarn jdk.dynalink.StandardOperation
-dontwarn jdk.dynalink.linker.GuardedInvocation
-dontwarn jdk.dynalink.linker.GuardingDynamicLinker
-dontwarn jdk.dynalink.linker.LinkRequest
-dontwarn jdk.dynalink.linker.LinkerServices
-dontwarn jdk.dynalink.linker.TypeBasedGuardingDynamicLinker
-dontwarn jdk.dynalink.linker.support.CompositeTypeBasedGuardingDynamicLinker
-dontwarn jdk.dynalink.linker.support.Guards
-dontwarn jdk.dynalink.support.ChainedCallSite


# ---------------------------------------------------------------------------
# PARANAMER LIBRARY (Constructor Parameter Reflection)
# ---------------------------------------------------------------------------
# Ignore missing Paranamer classes that may be referenced by libraries like Retrofit.
-dontwarn com.thoughtworks.paranamer.AdaptiveParanamer
-dontwarn com.thoughtworks.paranamer.Paranamer


# ---------------------------------------------------------------------------
# JAVA AWT / IMAGEIO (Desktop Graphics APIs)
# ---------------------------------------------------------------------------
# Prevent warnings for Java AWT/Graphics/ImageIO classes absent on Android.
-dontwarn java.awt.Graphics2D
-dontwarn java.awt.Graphics
-dontwarn java.awt.Image
-dontwarn java.awt.Point
-dontwarn java.awt.geom.Point2D$Double
-dontwarn java.awt.geom.Point2D
-dontwarn java.awt.geom.Rectangle2D$Double
-dontwarn java.awt.geom.Rectangle2D
-dontwarn java.awt.image.BufferedImage
-dontwarn java.awt.image.ImageObserver
-dontwarn java.awt.image.RenderedImage
-dontwarn javax.imageio.ImageIO
-dontwarn javax.json.spi.JsonProvider


# ---------------------------------------------------------------------------
# SUN INTERNAL REFLECTION UTILITIES
# ---------------------------------------------------------------------------
# Suppress warnings for sun.reflect.* internal APIs referenced by some libraries.
-dontwarn sun.reflect.ReflectionFactory


# ---------------------------------------------------------------------------
# OPTIMIZATION PASSES
# ---------------------------------------------------------------------------
# Controls how many optimization passes R8/ProGuard performs.
# More passes = potentially smaller code, but slower build time.
-optimizationpasses 7