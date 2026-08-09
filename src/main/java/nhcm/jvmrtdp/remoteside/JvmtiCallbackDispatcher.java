package nhcm.jvmrtdp.remoteside;

import nhcm.jvmrtdp.agent.NativeAgent;
import nhcm.jvmrtdp.api.jvmti.JvmtiClassFileEvent;
import nhcm.jvmrtdp.api.jvmti.JvmtiClassFileTransformer;
import nhcm.jvmrtdp.api.jvmti.JvmtiCapability;
import nhcm.jvmrtdp.api.jvmti.JvmtiCapabilityStatus;
import nhcm.jvmrtdp.api.jvmti.JvmtiEvent;
import nhcm.jvmrtdp.api.jvmti.JvmtiEventHandler;
import nhcm.jvmrtdp.api.jvmti.JvmtiEventType;
import nhcm.jvmrtdp.api.jvmti.JvmtiMethodArgument;
import nhcm.jvmrtdp.api.jvmti.JvmtiMethodEvent;
import nhcm.jvmrtdp.protocol.TextWireCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Process-wide bridge between native JVMTI callbacks and deployed Java handlers. */
public class JvmtiCallbackDispatcher {
    public enum Delivery { SYNC, ASYNC }

    private static final Map<String, Registration> REGISTRATIONS = new ConcurrentHashMap<String, Registration>();
    private static final CopyOnWriteArrayList<Registration> ORDERED = new CopyOnWriteArrayList<Registration>();
    private static final Map<JvmtiEventType, AtomicLong> ENABLE_COUNTS = new ConcurrentHashMap<JvmtiEventType, AtomicLong>();
    private static final ThreadPoolExecutor ASYNC = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(8192), new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "jvmrtdp-jvmti-callbacks");
                    thread.setDaemon(true);
                    return thread;
                }
            });
    private static final AtomicLong DELIVERED = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();
    private static volatile String lastFailure = "";

    private JvmtiCallbackDispatcher() {
    }

    public static String register(Object handler, Set<JvmtiEventType> events, Delivery delivery) {
        if (!(handler instanceof JvmtiEventHandler) && !(handler instanceof JvmtiClassFileTransformer)) {
            throw new IllegalArgumentException("Callback must implement JvmtiEventHandler or JvmtiClassFileTransformer");
        }
        EnumSet<JvmtiEventType> selected = events == null || events.isEmpty()
                ? EnumSet.noneOf(JvmtiEventType.class) : EnumSet.copyOf(events);
        if (handler instanceof JvmtiClassFileTransformer) selected.add(JvmtiEventType.CLASS_FILE_LOAD_HOOK);
        if (!(handler instanceof JvmtiEventHandler)) {
            for (JvmtiEventType event : selected) {
                if (event != JvmtiEventType.CLASS_FILE_LOAD_HOOK) {
                    throw new IllegalArgumentException("A transformer-only callback cannot receive " + event.wireName());
                }
            }
        }
        if (selected.isEmpty()) throw new IllegalArgumentException("At least one event must be selected");
        String id = UUID.randomUUID().toString();
        Registration registration = new Registration(id, handler, selected, delivery == null ? Delivery.ASYNC : delivery);
        List<JvmtiEventType> retained = new ArrayList<JvmtiEventType>();
        try {
            for (JvmtiEventType event : selected) {
                retain(event);
                retained.add(event);
            }
        } catch (RuntimeException failure) {
            for (JvmtiEventType event : retained) release(event);
            throw failure;
        }
        REGISTRATIONS.put(id, registration);
        ORDERED.add(registration);
        return id;
    }

    public static boolean unregister(String id) {
        Registration registration = REGISTRATIONS.remove(id);
        if (registration == null) return false;
        ORDERED.remove(registration);
        for (JvmtiEventType event : registration.events) release(event);
        return true;
    }

    public static void unregisterAll(Iterable<String> ids) {
        for (String id : ids) unregister(id);
    }

    public static List<String> registrations() {
        List<String> result = new ArrayList<String>();
        for (Registration registration : REGISTRATIONS.values()) {
            result.add(TextWireCodec.encode(registration.id, registration.handler.getClass().getName(),
                    eventNames(registration.events), registration.delivery.name().toLowerCase(Locale.ROOT),
                    Long.toString(registration.delivered.get()), Long.toString(registration.failed.get()),
                    registration.lastFailure));
        }
        Collections.sort(result);
        return result;
    }

    public static String statistics() {
        long[] nativeQueue = NativeAgent.eventQueueStatistics();
        return TextWireCodec.encode(Integer.toString(REGISTRATIONS.size()), Long.toString(DELIVERED.get()),
                Long.toString(FAILED.get()), lastFailure, Long.toString(nativeQueue[0]),
                Long.toString(nativeQueue[1]), Long.toString(nativeQueue[2]));
    }

    public static void dispatch(String eventName, Thread thread, String className, String methodName,
            String methodDescriptor, long location, Object subject, long value,
            String relatedClassName, String relatedMethodName, String relatedMethodDescriptor,
            long relatedLocation, String memberName, String memberDescriptor,
            Object secondarySubject, String text, Object receiver, String receiverError, Object[] methodArguments,
            String[] methodArgumentNames, int[] methodArgumentSlots, String[] methodArgumentErrors,
            int methodFlags, Object returnValue) {
        final JvmtiEventType type;
        try {
            type = JvmtiEventType.parse(eventName);
        } catch (RuntimeException ignored) {
            return;
        }
        final JvmtiEvent event;
        if (type == JvmtiEventType.METHOD_ENTRY || type == JvmtiEventType.METHOD_EXIT) {
            List<String> descriptors = parameterDescriptors(methodDescriptor);
            int count = methodArguments == null ? descriptors.size() : methodArguments.length;
            List<JvmtiMethodArgument> arguments = new ArrayList<JvmtiMethodArgument>(count);
            for (int index = 0; index < count; ++index) {
                Object argument = methodArguments != null && index < methodArguments.length
                        ? methodArguments[index] : null;
                String name = methodArgumentNames != null && index < methodArgumentNames.length
                        ? methodArgumentNames[index] : null;
                int slot = methodArgumentSlots != null && index < methodArgumentSlots.length
                        ? methodArgumentSlots[index] : -1;
                String error = methodArgumentErrors != null && index < methodArgumentErrors.length
                        ? methodArgumentErrors[index] : "argument metadata was not supplied";
                String descriptor = index < descriptors.size() ? descriptors.get(index) : "?";
                arguments.add(new JvmtiMethodArgument(index, slot, name, descriptor, argument, error));
            }
            event = new JvmtiMethodEvent(type, thread, className, methodName, methodDescriptor,
                    location, subject, value, relatedClassName, relatedMethodName,
                    relatedMethodDescriptor, relatedLocation, memberName, memberDescriptor,
                    secondarySubject, text, receiver, (methodFlags & 8) != 0,
                    receiverError, arguments, (methodFlags & 1) != 0,
                    (methodFlags & 2) != 0, (methodFlags & 4) != 0, returnValue);
        } else {
            event = new JvmtiEvent(type, thread, className, methodName, methodDescriptor,
                    location, subject, value, relatedClassName, relatedMethodName,
                    relatedMethodDescriptor, relatedLocation, memberName, memberDescriptor,
                    secondarySubject, text);
        }
        dispatch(event);
    }

    private static List<String> parameterDescriptors(String descriptor) {
        if (descriptor == null || descriptor.length() < 3 || descriptor.charAt(0) != '(') {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        int position = 1;
        while (position < descriptor.length() && descriptor.charAt(position) != ')') {
            int start = position;
            while (position < descriptor.length() && descriptor.charAt(position) == '[') position++;
            if (position >= descriptor.length()) return Collections.emptyList();
            if (descriptor.charAt(position++) == 'L') {
                int semicolon = descriptor.indexOf(';', position);
                if (semicolon < 0) return Collections.emptyList();
                position = semicolon + 1;
            }
            result.add(descriptor.substring(start, position));
        }
        return result;
    }

    private static void dispatch(final JvmtiEvent event) {
        for (final Registration registration : ORDERED) {
            if (!registration.events.contains(event.type())
                    || !(registration.handler instanceof JvmtiEventHandler)) continue;
            if (registration.delivery == Delivery.SYNC) deliver(registration, event);
            else {
                try {
                    ASYNC.execute(new Runnable() {
                        @Override public void run() { deliver(registration, event); }
                    });
                } catch (RejectedExecutionException full) {
                    failure(registration, new IllegalStateException("JVMTI callback queue is full"));
                }
            }
        }
    }

    public static byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            java.security.ProtectionDomain protectionDomain, byte[] bytes) {
        JvmtiClassFileEvent originalEvent = new JvmtiClassFileEvent(
                loader, className, classBeingRedefined, protectionDomain, bytes);
        dispatch(new JvmtiEvent(JvmtiEventType.CLASS_FILE_LOAD_HOOK, null, className,
                null, null, 0, originalEvent, bytes.length));
        byte[] current = bytes;
        for (Registration registration : ORDERED) {
            if (!(registration.handler instanceof JvmtiClassFileTransformer)) continue;
            try {
                byte[] replacement = ((JvmtiClassFileTransformer) registration.handler).transform(
                        new JvmtiClassFileEvent(loader, className, classBeingRedefined,
                                protectionDomain, current));
                if (replacement != null) {
                    requireClassBytes(replacement);
                    current = replacement;
                }
                registration.delivered.incrementAndGet();
                DELIVERED.incrementAndGet();
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                failure(registration, failure);
            }
        }
        return current == bytes ? null : current;
    }

    private static void deliver(Registration registration, JvmtiEvent event) {
        try {
            ((JvmtiEventHandler) registration.handler).onEvent(event);
            registration.delivered.incrementAndGet();
            DELIVERED.incrementAndGet();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            failure(registration, failure);
        }
    }

    private static void failure(Registration registration, Throwable failure) {
        String description = failure.toString();
        registration.failed.incrementAndGet();
        registration.lastFailure = description;
        FAILED.incrementAndGet();
        lastFailure = description;
    }

    private static synchronized void retain(JvmtiEventType event) {
        AtomicLong count = ENABLE_COUNTS.get(event);
        if (count == null) {
            count = new AtomicLong();
            ENABLE_COUNTS.put(event, count);
        }
        if (count.getAndIncrement() == 0) {
            try {
                requireCapability(event);
                NativeAgent.setEventNotification(event.wireName(), true);
            } catch (RuntimeException failure) {
                count.decrementAndGet();
                throw failure;
            }
        }
    }

    private static void requireCapability(JvmtiEventType event) {
        JvmtiCapability required = event.requiredCapability();
        if (required == null) return;
        for (JvmtiCapabilityStatus status : NativeAgent.capabilityStatuses()) {
            if (status.capability() != required) continue;
            if (status.enabled()) return;
            throw new IllegalStateException("JVMTI event " + event.wireName() + " requires "
                    + required.wireName() + "; this VM did not grant it"
                    + (status.potential() ? "" : " in the current phase. Start the JVM with -agentpath "
                            + "to acquire OnLoad-only capabilities"));
        }
        throw new IllegalStateException("JVMTI did not report capability " + required.wireName());
    }

    private static synchronized void release(JvmtiEventType event) {
        AtomicLong count = ENABLE_COUNTS.get(event);
        if (count == null || count.get() == 0) return;
        if (count.decrementAndGet() == 0) {
            try { NativeAgent.setEventNotification(event.wireName(), false); }
            catch (RuntimeException ignored) { }
        }
    }

    private static String eventNames(Set<JvmtiEventType> events) {
        StringBuilder result = new StringBuilder();
        for (JvmtiEventType event : events) {
            if (result.length() != 0) result.append(',');
            result.append(event.wireName());
        }
        return result.toString();
    }

    private static void requireClassBytes(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != (byte) 0xCA || bytes[1] != (byte) 0xFE
                || bytes[2] != (byte) 0xBA || bytes[3] != (byte) 0xBE) {
            throw new IllegalArgumentException("Transformer returned invalid class bytes");
        }
    }

    private static class Registration {
        private final String id;
        private final Object handler;
        private final EnumSet<JvmtiEventType> events;
        private final Delivery delivery;
        private final AtomicLong delivered = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private volatile String lastFailure = "";

        private Registration(String id, Object handler, EnumSet<JvmtiEventType> events, Delivery delivery) {
            this.id = id;
            this.handler = handler;
            this.events = events;
            this.delivery = delivery;
        }
    }
}
