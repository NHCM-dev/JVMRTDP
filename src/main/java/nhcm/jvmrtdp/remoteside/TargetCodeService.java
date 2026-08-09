package nhcm.jvmrtdp.remoteside;

import nhcm.jvmrtdp.agent.NativeAgent;
import nhcm.jvmrtdp.api.jvmti.JvmtiEventType;
import nhcm.jvmrtdp.protocol.RemoteObjectDescriptor;
import nhcm.jvmrtdp.protocol.TextWireCodec;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Target-side lifecycle for deployed classes, JARs, execution and JVMTI Java callbacks. */
public class TargetCodeService implements AutoCloseable {
    public enum DefinitionMode { CHILD, SAME_LOADER }
    public enum JarScope { CHILD, SYSTEM, BOOTSTRAP }

    private static final int MAX_UPLOAD_BYTES = 256 * 1024 * 1024;
    private static final int MAX_UPLOAD_CHUNK_BYTES = 1024 * 1024;

    private final TargetObjectService objects;
    private final Map<String, Deployment> deployments = new ConcurrentHashMap<String, Deployment>();
    private final Map<String, Upload> uploads = new ConcurrentHashMap<String, Upload>();
    private final List<String> callbackIds = Collections.synchronizedList(new ArrayList<String>());
    private final Map<String, String> callbackDeployments = new ConcurrentHashMap<String, String>();
    private final List<Path> temporaryJars = Collections.synchronizedList(new ArrayList<Path>());

    public TargetCodeService(TargetObjectService objects) {
        this.objects = objects;
    }

    public String deploy(String name, Map<String, byte[]> classes, String anchorClass, DefinitionMode mode) {
        if (classes == null || classes.isEmpty()) throw new IllegalArgumentException("No classes were supplied");
        ClassLoader targetLoader = classLoader(anchorClass);
        String id = UUID.randomUUID().toString();
        Map<String, Class<?>> defined = new LinkedHashMap<String, Class<?>>();
        ClassLoader deploymentLoader;
        if (mode == DefinitionMode.SAME_LOADER) {
            deploymentLoader = targetLoader;
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                defined.put(entry.getKey(), NativeAgent.defineClass(entry.getKey(), entry.getValue(), targetLoader));
            }
        } else {
            TargetCodeClassLoader loader = new TargetCodeClassLoader(targetLoader, classes, new URL[0]);
            deploymentLoader = loader;
            for (String className : classes.keySet()) {
                try {
                    defined.put(className, Class.forName(className, false, loader));
                } catch (ClassNotFoundException exception) {
                    throw new IllegalStateException("Cannot define deployed class " + className, exception);
                }
            }
        }
        Deployment deployment = new Deployment(id, safeName(name), deploymentLoader, targetLoader, defined, mode.name());
        deployments.put(id, deployment);
        return deployment.describe();
    }

    public String beginUpload(long expectedBytes, String sha256) {
        if (expectedBytes < 1 || expectedBytes > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("Upload size must be between 1 and " + MAX_UPLOAD_BYTES);
        }
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Upload SHA-256 must contain 64 hexadecimal characters");
        }
        String id = UUID.randomUUID().toString();
        uploads.put(id, new Upload(expectedBytes, sha256.toLowerCase(java.util.Locale.ROOT)));
        return id;
    }

    public long appendUpload(String id, int chunkIndex, byte[] bytes) {
        Upload upload = uploads.get(id);
        if (upload == null) throw new IllegalArgumentException("Unknown upload: " + id);
        return upload.append(chunkIndex, bytes);
    }

    public boolean abortUpload(String id) {
        return uploads.remove(id) != null;
    }

    public String deployUpload(String name, String uploadId, String anchorClass, DefinitionMode mode) {
        return deploy(name, nhcm.jvmrtdp.protocol.CodeBundleCodec.decodeBytes(takeUpload(uploadId)),
                anchorClass, mode);
    }

    public String addJarUpload(String name, String uploadId, String anchorClass, JarScope scope) {
        return addJar(name, takeUpload(uploadId), anchorClass, scope);
    }

    public String addJar(String name, byte[] jarBytes, String anchorClass, JarScope scope) {
        requireJar(jarBytes);
        try {
            Path jar = Files.createTempFile("jvmrtdp-", ".jar").toAbsolutePath().normalize();
            Files.write(jar, jarBytes, StandardOpenOption.TRUNCATE_EXISTING);
            jar.toFile().deleteOnExit();
            temporaryJars.add(jar);
            if (scope == JarScope.SYSTEM) {
                NativeAgent.addToSystemClassLoaderSearch(jar.toString());
                return recordClasspathJar(name, ClassLoader.getSystemClassLoader(), scope, jar);
            }
            if (scope == JarScope.BOOTSTRAP) {
                NativeAgent.addToBootstrapClassLoaderSearch(jar.toString());
                return recordClasspathJar(name, null, scope, jar);
            }
            ClassLoader targetLoader = classLoader(anchorClass);
            TargetCodeClassLoader loader = new TargetCodeClassLoader(targetLoader,
                    Collections.<String, byte[]>emptyMap(), new URL[]{jar.toUri().toURL()});
            String id = UUID.randomUUID().toString();
            Deployment deployment = new Deployment(id, safeName(name), loader, targetLoader,
                    Collections.<String, Class<?>>emptyMap(), scope.name());
            deployments.put(id, deployment);
            return deployment.describe();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot stage JAR in target JVM", exception);
        }
    }

    public RemoteObjectDescriptor execute(String deploymentId, String className, String methodName,
            String descriptor, long receiverId, long[] argumentIds) {
        Deployment deployment = requireDeployment(deploymentId);
        Class<?> type = deployment.loadClass(className);
        Method method = findMethod(type, methodName, descriptor);
        Object receiver = Modifier.isStatic(method.getModifiers()) ? null : objects.resolveExternal(receiverId);
        Object[] arguments = new Object[argumentIds.length];
        for (int index = 0; index < argumentIds.length; index++) arguments[index] = objects.resolveExternal(argumentIds[index]);
        try {
            if (!method.isAccessible()) method.setAccessible(true);
            return objects.storeExternal(method.invoke(receiver, arguments));
        } catch (InvocationTargetException exception) {
            throw propagate("Deployed code threw", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot execute " + className + "." + methodName + descriptor, exception);
        }
    }

    public String registerCallback(String deploymentId, String handlerClass, String events, String delivery) {
        Deployment deployment = requireDeployment(deploymentId);
        Object handler = instantiate(deployment.loadClass(handlerClass));
        EnumSet<JvmtiEventType> selected = EnumSet.noneOf(JvmtiEventType.class);
        if (events != null && !events.trim().isEmpty()) {
            for (String event : events.split(",")) selected.add(JvmtiEventType.parse(event));
        }
        final JvmtiCallbackDispatcher.Delivery mode;
        if ("sync".equalsIgnoreCase(delivery)) mode = JvmtiCallbackDispatcher.Delivery.SYNC;
        else if ("async".equalsIgnoreCase(delivery)) mode = JvmtiCallbackDispatcher.Delivery.ASYNC;
        else throw new IllegalArgumentException("Callback delivery must be sync or async");
        String id = JvmtiCallbackDispatcher.register(handler, selected, mode);
        callbackIds.add(id);
        callbackDeployments.put(id, deploymentId);
        return id;
    }

    public boolean unregisterCallback(String callbackId) {
        callbackIds.remove(callbackId);
        callbackDeployments.remove(callbackId);
        return JvmtiCallbackDispatcher.unregister(callbackId);
    }

    public List<String> callbacks() {
        return JvmtiCallbackDispatcher.registrations();
    }

    public String callbackStatistics() {
        return JvmtiCallbackDispatcher.statistics();
    }

    public List<String> deployments() {
        List<String> result = new ArrayList<String>();
        for (Deployment deployment : deployments.values()) result.add(deployment.describe());
        Collections.sort(result);
        return result;
    }

    public boolean closeDeployment(String id) {
        Deployment deployment = deployments.remove(id);
        if (deployment == null) return false;
        for (Map.Entry<String, String> callback : new ArrayList<Map.Entry<String, String>>(
                callbackDeployments.entrySet())) {
            if (id.equals(callback.getValue())) unregisterCallback(callback.getKey());
        }
        deployment.close();
        return true;
    }

    public Class<?> deploymentClass(String deploymentId, String className) {
        return requireDeployment(deploymentId).loadClass(className);
    }

    @Override
    public void close() {
        JvmtiCallbackDispatcher.unregisterAll(new ArrayList<String>(callbackIds));
        callbackIds.clear();
        callbackDeployments.clear();
        for (Deployment deployment : deployments.values()) deployment.close();
        deployments.clear();
        uploads.clear();
        // JARs added to a JVM search path can remain open on Windows. deleteOnExit is intentional.
        temporaryJars.clear();
    }

    private String recordClasspathJar(String name, ClassLoader loader, JarScope scope, Path jar) {
        String id = UUID.randomUUID().toString();
        Deployment deployment = new Deployment(id, safeName(name), loader, loader,
                Collections.<String, Class<?>>emptyMap(), scope.name() + ":" + jar);
        deployments.put(id, deployment);
        return deployment.describe();
    }

    private ClassLoader classLoader(String anchorClass) {
        if (anchorClass == null || anchorClass.trim().isEmpty() || "system".equalsIgnoreCase(anchorClass)) {
            return ClassLoader.getSystemClassLoader();
        }
        if ("bootstrap".equalsIgnoreCase(anchorClass)) return null;
        return NativeAgent.findLoadedClass(anchorClass).getClassLoader();
    }

    private Deployment requireDeployment(String id) {
        Deployment deployment = deployments.get(id);
        if (deployment == null) throw new IllegalArgumentException("Unknown deployment: " + id);
        return deployment;
    }

    private byte[] takeUpload(String id) {
        Upload upload = uploads.remove(id);
        if (upload == null) throw new IllegalArgumentException("Unknown upload: " + id);
        return upload.finish();
    }

    private static Object instantiate(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            if (!constructor.isAccessible()) constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InvocationTargetException exception) {
            throw propagate("Callback constructor threw", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("Callback requires an accessible no-argument constructor: " + type.getName(), exception);
        }
    }

    private static Method findMethod(Class<?> type, String name, String descriptor) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && JvmDescriptors.of(method).equals(descriptor)) return method;
            }
        }
        throw new IllegalArgumentException("Method was not found: " + type.getName() + "." + name + descriptor);
    }

    private static RuntimeException propagate(String operation, Throwable failure) {
        if (failure instanceof RuntimeException) return (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        return new IllegalStateException(operation + ": " + failure, failure);
    }

    private static String safeName(String name) {
        return name == null || name.trim().isEmpty() ? "deployment" : name.trim();
    }

    private static void requireJar(byte[] bytes) {
        if (bytes == null || bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
            throw new IllegalArgumentException("Invalid JAR/ZIP bytes");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static class Upload {
        private final long expectedBytes;
        private final String expectedSha256;
        private final ByteArrayOutputStream bytes;
        private int nextChunk;

        private Upload(long expectedBytes, String expectedSha256) {
            this.expectedBytes = expectedBytes;
            this.expectedSha256 = expectedSha256;
            this.bytes = new ByteArrayOutputStream((int) Math.min(expectedBytes, MAX_UPLOAD_CHUNK_BYTES));
        }

        private synchronized long append(int chunkIndex, byte[] chunk) {
            if (chunkIndex != nextChunk) {
                throw new IllegalArgumentException("Expected upload chunk " + nextChunk + " but received " + chunkIndex);
            }
            if (chunk == null || chunk.length == 0 || chunk.length > MAX_UPLOAD_CHUNK_BYTES) {
                throw new IllegalArgumentException("Upload chunk must contain between 1 and "
                        + MAX_UPLOAD_CHUNK_BYTES + " bytes");
            }
            if ((long) bytes.size() + chunk.length > expectedBytes) {
                throw new IllegalArgumentException("Upload exceeds its declared size");
            }
            bytes.write(chunk, 0, chunk.length);
            nextChunk++;
            return bytes.size();
        }

        private synchronized byte[] finish() {
            if (bytes.size() != expectedBytes) {
                throw new IllegalArgumentException("Incomplete upload: expected " + expectedBytes
                        + " bytes but received " + bytes.size());
            }
            byte[] result = bytes.toByteArray();
            String actual = sha256(result);
            if (!expectedSha256.equals(actual)) {
                throw new IllegalArgumentException("Upload SHA-256 mismatch: expected "
                        + expectedSha256 + " but received " + actual);
            }
            return result;
        }
    }

    private static class Deployment implements AutoCloseable {
        private final String id;
        private final String name;
        private ClassLoader loader;
        private ClassLoader targetLoader;
        private final Map<String, Class<?>> defined;
        private final String mode;

        private Deployment(String id, String name, ClassLoader loader, ClassLoader targetLoader,
                Map<String, Class<?>> defined, String mode) {
            this.id = id;
            this.name = name;
            this.loader = loader;
            this.targetLoader = targetLoader;
            this.defined = new ConcurrentHashMap<String, Class<?>>(defined);
            this.mode = mode;
        }

        private Class<?> loadClass(String className) {
            Class<?> existing = defined.get(className);
            if (existing != null) return existing;
            try {
                Class<?> loaded = Class.forName(className, false, loader);
                defined.put(className, loaded);
                return loaded;
            } catch (ClassNotFoundException exception) {
                throw new IllegalArgumentException("Class is not available in deployment " + id + ": " + className, exception);
            }
        }

        private String describe() {
            return TextWireCodec.encode(id, name, mode, Integer.toString(defined.size()), loaderName(loader), loaderName(targetLoader));
        }

        @Override public void close() {
            if (loader instanceof URLClassLoader) {
                try { ((URLClassLoader) loader).close(); } catch (IOException ignored) { }
            }
            defined.clear();
        }
    }

    private static String loaderName(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    /** Child-first for deployed classes; target application and agent API are both visible. */
    private static class TargetCodeClassLoader extends URLClassLoader {
        private ClassLoader targetLoader;
        private final Map<String, byte[]> definitions;
        private ClassLoader agentLoader = TargetCodeService.class.getClassLoader();

        private TargetCodeClassLoader(ClassLoader targetLoader, Map<String, byte[]> definitions, URL[] urls) {
            super(urls, null);
            this.targetLoader = targetLoader;
            this.definitions = new ConcurrentHashMap<String, byte[]>(definitions);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null && definitions.containsKey(name)) type = findClass(name);
                if (type == null) {
                    try { type = targetLoader == null ? Class.forName(name, false, null) : targetLoader.loadClass(name); }
                    catch (ClassNotFoundException ignored) { }
                }
                if (type == null) {
                    try { type = agentLoader.loadClass(name); }
                    catch (ClassNotFoundException ignored) { }
                }
                if (type == null) type = super.loadClass(name, false);
                if (resolve) resolveClass(type);
                return type;
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = definitions.remove(name);
            if (bytes != null) return defineClass(name, bytes, 0, bytes.length);
            return super.findClass(name);
        }
    }
}
