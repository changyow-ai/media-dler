# Gradual R8 adoption: AndroidX, Google, Kotlin and kotlinx libraries are eligible
# for shrinking, optimization and renaming. Keep every other class and member,
# including app/persisted models, yt-dlp/Jackson, OpenCC, sharing and JNI.
# Negated prefixes must precede the final catch-all; do not add allow* modifiers.
-keep class !androidx.**,!com.google.**,!kotlin.**,!kotlinx.**,** { *; }

# Preserve metadata used by reflection, serialization and native bridges.
# Dependency consumer rules remain active for optimized libraries, including
# coroutine dispatcher/atomic-field and kotlinx.serialization entrypoint rules.
-keepattributes *

# Release instrumentation shares these public AndroidX APIs with the target
# app. AGP deduplicates them from the test APK even when app R8 inlines/removes
# them, so preserve the facades used by AndroidJUnitRunner and its utilities.
-keep class androidx.tracing.Trace { public *; }
-keep class androidx.concurrent.futures.CallbackToFutureAdapter { public *; }

# Kept third-party libraries also contain optional desktop/provider integrations.
# Suppress only the exact absent types reported by R8, not entire dependencies.

# Commons Compress optional codecs/Pack200. Model archives use tar + bzip2.
-dontwarn com.github.luben.zstd.BufferPool
-dontwarn com.github.luben.zstd.ZstdInputStream
-dontwarn com.github.luben.zstd.ZstdOutputStream
-dontwarn org.brotli.dec.BrotliInputStream
-dontwarn org.objectweb.asm.AnnotationVisitor
-dontwarn org.objectweb.asm.Attribute
-dontwarn org.objectweb.asm.ClassReader
-dontwarn org.objectweb.asm.ClassVisitor
-dontwarn org.objectweb.asm.FieldVisitor
-dontwarn org.objectweb.asm.Label
-dontwarn org.objectweb.asm.MethodVisitor
-dontwarn org.objectweb.asm.Type
-dontwarn org.tukaani.xz.ARMOptions
-dontwarn org.tukaani.xz.ARMThumbOptions
-dontwarn org.tukaani.xz.DeltaOptions
-dontwarn org.tukaani.xz.FilterOptions
-dontwarn org.tukaani.xz.FinishableOutputStream
-dontwarn org.tukaani.xz.FinishableWrapperOutputStream
-dontwarn org.tukaani.xz.IA64Options
-dontwarn org.tukaani.xz.LZMA2InputStream
-dontwarn org.tukaani.xz.LZMA2Options
-dontwarn org.tukaani.xz.LZMAInputStream
-dontwarn org.tukaani.xz.LZMAOutputStream
-dontwarn org.tukaani.xz.MemoryLimitException
-dontwarn org.tukaani.xz.PowerPCOptions
-dontwarn org.tukaani.xz.SPARCOptions
-dontwarn org.tukaani.xz.SingleXZInputStream
-dontwarn org.tukaani.xz.UnsupportedOptionsException
-dontwarn org.tukaani.xz.X86Options
-dontwarn org.tukaani.xz.XZ
-dontwarn org.tukaani.xz.XZInputStream
-dontwarn org.tukaani.xz.XZOutputStream

# jUPnP optional desktop HTTP, servlet, Jetty and OSGi adapters.
# Android sharing supplies its own transport instead.
-dontwarn com.sun.net.httpserver.Headers
-dontwarn com.sun.net.httpserver.HttpExchange
-dontwarn javax.servlet.AsyncContext
-dontwarn javax.servlet.AsyncEvent
-dontwarn javax.servlet.AsyncListener
-dontwarn javax.servlet.Servlet
-dontwarn javax.servlet.ServletException
-dontwarn javax.servlet.ServletInputStream
-dontwarn javax.servlet.ServletOutputStream
-dontwarn javax.servlet.ServletRequest
-dontwarn javax.servlet.ServletResponse
-dontwarn javax.servlet.http.Cookie
-dontwarn javax.servlet.http.HttpServlet
-dontwarn javax.servlet.http.HttpServletRequest
-dontwarn javax.servlet.http.HttpServletResponse
-dontwarn org.eclipse.jetty.client.HttpClient
-dontwarn org.eclipse.jetty.client.api.ContentProvider$Typed
-dontwarn org.eclipse.jetty.client.api.ContentProvider
-dontwarn org.eclipse.jetty.client.api.ContentResponse
-dontwarn org.eclipse.jetty.client.api.Request
-dontwarn org.eclipse.jetty.client.api.Response
-dontwarn org.eclipse.jetty.client.util.BytesContentProvider
-dontwarn org.eclipse.jetty.client.util.StringContentProvider
-dontwarn org.eclipse.jetty.http.HttpField
-dontwarn org.eclipse.jetty.http.HttpFields
-dontwarn org.eclipse.jetty.http.HttpHeader
-dontwarn org.eclipse.jetty.http.HttpVersion
-dontwarn org.eclipse.jetty.server.Connector
-dontwarn org.eclipse.jetty.server.Handler
-dontwarn org.eclipse.jetty.server.Request
-dontwarn org.eclipse.jetty.server.Server
-dontwarn org.eclipse.jetty.server.ServerConnector
-dontwarn org.eclipse.jetty.servlet.ServletContextHandler
-dontwarn org.eclipse.jetty.servlet.ServletHolder
-dontwarn org.eclipse.jetty.util.thread.QueuedThreadPool
-dontwarn org.osgi.framework.BundleContext
-dontwarn org.osgi.service.component.ComponentContext
-dontwarn org.osgi.service.component.annotations.Activate
-dontwarn org.osgi.service.component.annotations.Component
-dontwarn org.osgi.service.component.annotations.ConfigurationPolicy
-dontwarn org.osgi.service.component.annotations.Deactivate
-dontwarn org.osgi.service.component.annotations.Modified
-dontwarn org.osgi.service.component.annotations.Reference
-dontwarn org.osgi.service.http.HttpContext
-dontwarn org.osgi.service.http.HttpService
-dontwarn org.osgi.service.http.NamespaceException
-dontwarn org.osgi.service.metatype.annotations.AttributeDefinition
-dontwarn org.osgi.service.metatype.annotations.Designate
-dontwarn org.osgi.service.metatype.annotations.ObjectClassDefinition

# SSHD optional EdDSA provider, Unix APR agent and Kerberos/JAAS adapters.
-dontwarn javax.security.auth.callback.NameCallback
-dontwarn javax.security.auth.login.AppConfigurationEntry$LoginModuleControlFlag
-dontwarn javax.security.auth.login.AppConfigurationEntry
-dontwarn javax.security.auth.login.Configuration
-dontwarn javax.security.auth.login.CredentialException
-dontwarn javax.security.auth.login.FailedLoginException
-dontwarn javax.security.auth.login.LoginContext
-dontwarn net.i2p.crypto.eddsa.EdDSAKey
-dontwarn net.i2p.crypto.eddsa.EdDSAPrivateKey
-dontwarn net.i2p.crypto.eddsa.EdDSAPublicKey
-dontwarn net.i2p.crypto.eddsa.math.Curve
-dontwarn net.i2p.crypto.eddsa.math.GroupElement
-dontwarn net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec
-dontwarn net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
-dontwarn net.i2p.crypto.eddsa.spec.EdDSAParameterSpec
-dontwarn net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
-dontwarn net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
-dontwarn org.apache.tomcat.jni.Error
-dontwarn org.apache.tomcat.jni.File
-dontwarn org.apache.tomcat.jni.Library
-dontwarn org.apache.tomcat.jni.Local
-dontwarn org.apache.tomcat.jni.Pool
-dontwarn org.apache.tomcat.jni.Socket
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.MessageProp
-dontwarn org.ietf.jgss.Oid

# OpenCC's optional Jieba segmenter.
-dontwarn com.huaban.analysis.jieba.JiebaSegmenter

# Desktop JDK metadata/utility APIs in retained Jackson, OpenCC, Commons Lang
# and SSHD code. These types were already absent from the unminified APK.
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.beans.Transient
-dontwarn java.lang.invoke.MethodHandleProxies
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.rmi.RemoteException
-dontwarn java.rmi.ServerException
-dontwarn javax.management.MBeanException
-dontwarn javax.management.ReflectionException
-dontwarn org.w3c.dom.bootstrap.DOMImplementationRegistry
