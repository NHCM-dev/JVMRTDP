package nhcm.jvmrtdp.controllerside.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScriptProgram {
    private final List<ScriptInstruction> instructions;
    private final Map<String, Integer> labels;

    public ScriptProgram(List<ScriptInstruction> instructions, Map<String, Integer> labels) {
        this.instructions = Collections.unmodifiableList(new ArrayList<ScriptInstruction>(instructions));
        this.labels = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(labels));
    }

    public List<ScriptInstruction> instructions() {
        return instructions;
    }

    public int label(String name) {
        Integer index = labels.get(name);
        if (index == null) throw new IllegalArgumentException("Unknown script label: " + name);
        return index;
    }
}
