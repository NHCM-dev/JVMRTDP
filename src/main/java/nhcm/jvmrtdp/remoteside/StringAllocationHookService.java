package nhcm.jvmrtdp.remoteside;

import nhcm.jvmrtdp.agent.NativeAgent;
import nhcm.jvmrtdp.api.jvmti.JvmtiCapability;
import nhcm.jvmrtdp.api.jvmti.JvmtiCapabilityStatus;
import nhcm.jvmrtdp.api.jvmti.JvmtiEventType;

import java.util.HashSet;
import java.util.Set;

/** Target-side ownership bridge for native String allocation filters and JVMTI events. */
public final class StringAllocationHookService {
    private static final Set<String> REGISTRATIONS = new HashSet<String>();

    private StringAllocationHookService() { }

    public static synchronized void set(String id, String contentPattern,
            String creatorClassPattern, String creatorMethodPattern,
            String creatorDescriptorPattern, boolean caseSensitive) {
        boolean added = !REGISTRATIONS.contains(id);
        if (added) {
            ensureCapability(JvmtiCapability.CAN_GENERATE_VM_OBJECT_ALLOC_EVENTS);
            ensureCapability(JvmtiCapability.CAN_GENERATE_METHOD_EXIT_EVENTS);
            ensureCapability(JvmtiCapability.CAN_ACCESS_LOCAL_VARIABLES);
            REGISTRATIONS.add(id);
            boolean allocationRetained = false;
            try {
                JvmtiCallbackDispatcher.retainInfrastructureEvent(JvmtiEventType.VM_OBJECT_ALLOC);
                allocationRetained = true;
                JvmtiCallbackDispatcher.retainInfrastructureEvent(JvmtiEventType.METHOD_EXIT);
            } catch (RuntimeException failure) {
                if (allocationRetained) {
                    JvmtiCallbackDispatcher.releaseInfrastructureEvent(JvmtiEventType.VM_OBJECT_ALLOC);
                }
                REGISTRATIONS.remove(id);
                throw failure;
            }
        }
        try {
            NativeAgent.setStringAllocationHook(id, contentPattern,
                    creatorClassPattern, creatorMethodPattern, creatorDescriptorPattern,
                    caseSensitive, true);
        } catch (RuntimeException failure) {
            if (added) {
                REGISTRATIONS.remove(id);
                JvmtiCallbackDispatcher.releaseInfrastructureEvent(JvmtiEventType.METHOD_EXIT);
                JvmtiCallbackDispatcher.releaseInfrastructureEvent(JvmtiEventType.VM_OBJECT_ALLOC);
            }
            throw failure;
        }
    }

    public static synchronized boolean remove(String id) {
        if (!REGISTRATIONS.remove(id)) return false;
        try {
            NativeAgent.setStringAllocationHook(id, "*", "*", "*", "*", true, false);
        } finally {
            JvmtiCallbackDispatcher.releaseInfrastructureEvent(JvmtiEventType.METHOD_EXIT);
            JvmtiCallbackDispatcher.releaseInfrastructureEvent(JvmtiEventType.VM_OBJECT_ALLOC);
        }
        return true;
    }

    private static void ensureCapability(JvmtiCapability capability) {
        JvmtiCapabilityStatus status = status(capability, NativeAgent.capabilityStatuses());
        if (status != null && status.enabled()) return;
        if (status != null && status.potential()) {
            status = status(capability, NativeAgent.addCapabilities(capability));
            if (status != null && status.enabled()) return;
        }
        throw new IllegalStateException("String allocation hooks require "
                + capability.wireName() + "; start the target with -agentpath if the live VM "
                + "can no longer grant it");
    }

    private static JvmtiCapabilityStatus status(JvmtiCapability capability,
            Iterable<JvmtiCapabilityStatus> statuses) {
        for (JvmtiCapabilityStatus status : statuses) {
            if (status.capability() == capability) return status;
        }
        return null;
    }
}
