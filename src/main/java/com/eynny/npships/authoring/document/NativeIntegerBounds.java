package com.eynny.npships.authoring.document;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Conservative scalar domains from installed native bytecode. A domain needs a direct top-level
 * integer serializer and a consistent constant clamp on the same instance field. Unknown writes
 * invalidate it unless that method repairs every outgoing path with the clamp. Native NBT reads and
 * constructor initialization are separate admission boundaries, not evidence of wider domains.
 * Analysis never invokes a native constructor, reader, setter or world callback.
 */
public final class NativeIntegerBounds {
    private NativeIntegerBounds() {}

    private static final String TAG = "net/minecraft/nbt/CompoundTag";
    private static final Set<String> SERIALIZERS =
            Set.of(
                    "read",
                    "write",
                    "load",
                    "save",
                    "loadAdditional",
                    "saveAdditional",
                    "readAdditionalSaveData",
                    "addAdditionalSaveData",
                    "deserializeNBT",
                    "serializeNBT");
    private static final ClassValue<Map<String, DataSuggestions.NativeField>> CACHE =
            new ClassValue<>() {
                @Override
                protected Map<String, DataSuggestions.NativeField> computeValue(Class<?> type) {
                    return new Scan(type).run();
                }
            };

    /** Keys are actual root NBT keys; the document adapter owns any display-path prefix. */
    public static Map<String, DataSuggestions.NativeField> of(Class<?> type) {
        return CACHE.get(type);
    }

    private record Field(String owner, String name) {}

    private record Bounds(int min, int max) {
        boolean contains(int value) {
            return value >= min && value <= max;
        }
    }

    private record Read(String key) {}

    private enum Origin {
        THIS,
        ROOT_TAG
    }

    private record Write(int index, Object value, boolean receiver) {}

    private static final class Scan {
        private final Class<?> type;
        private final Map<String, ClassNode> classes = new LinkedHashMap<>();
        private final List<Method> methods = new ArrayList<>();
        private final Map<String, Map<String, Field>> declaredFields = new HashMap<>();

        Scan(Class<?> type) {
            this.type = type;
        }

        Map<String, DataSuggestions.NativeField> run() {
            try {
                if (!readClasses() || !readMethods()) return Map.of();
                var active = serializers();
                var keys = new LinkedHashMap<Field, Set<String>>();
                var owners = new HashMap<String, Set<Field>>();
                var unsupported = new HashSet<String>();
                for (var method : methods) {
                    if (!active.contains(method)) continue;
                    for (int i = 0; i < method.frames.length; i++) {
                        if (!(method.code.instructions.get(i) instanceof MethodInsnNode call)
                                || !call.owner.equals(TAG)
                                || !call.name.equals("putInt")
                                || !call.desc.equals("(Ljava/lang/String;I)V")) continue;
                        var values = arguments(method.frames[i], call);
                        if (values.size() != 3
                                || origin(values.get(0)) != Origin.ROOT_TAG
                                || !(origin(values.get(1)) instanceof String key)) continue;
                        if (!(origin(values.get(2)) instanceof Field field)) {
                            unsupported.add(key);
                            continue;
                        }
                        if (keys.size() >= 512) return Map.of();
                        keys.computeIfAbsent(field, ignored -> new HashSet<>()).add(key);
                        owners.computeIfAbsent(key, ignored -> new HashSet<>()).add(field);
                    }
                }
                var result = new LinkedHashMap<String, DataSuggestions.NativeField>();
                for (var entry : keys.entrySet()) {
                    if (entry.getValue().size() != 1) continue;
                    String key = entry.getValue().iterator().next();
                    if (owners.get(key).size() != 1
                            || unsupported.contains(key)
                            || key.indexOf('.') >= 0
                            || key.indexOf('[') >= 0) continue;
                    var bounds = bounds(entry.getKey(), key);
                    if (bounds != null)
                        result.put(
                                key,
                                new DataSuggestions.NativeField(
                                        key, bounds.min, bounds.max, Map.of()));
                }
                return Map.copyOf(result);
            } catch (IOException
                    | AnalyzerException
                    | RuntimeException
                    | LinkageError unavailable) {
                // Missing resources, unsupported control flow and exhausted budgets remain unknown.
                return Map.of();
            }
        }

        boolean readClasses() throws IOException {
            for (Class<?> at = type; at != null; at = at.getSuperclass()) {
                if (classes.size() >= 48) return false;
                String name = at.getName().replace('.', '/');
                try (var stream = NativeSerializationFields.resource(name, type.getClassLoader())) {
                    if (stream == null) return false;
                    var bytes = stream.readNBytes(1_048_577);
                    if (bytes.length > 1_048_576) return false;
                    var node = new ClassNode();
                    new ClassReader(bytes)
                            .accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    classes.put(name, node);
                    var members = new HashMap<String, Field>();
                    for (var field : node.fields)
                        if (field.desc.equals("I") && (field.access & Opcodes.ACC_STATIC) == 0)
                            members.put(field.name, new Field(name, field.name));
                    declaredFields.put(name, members);
                }
            }
            return true;
        }

        boolean readMethods() throws AnalyzerException {
            int count = 0;
            int instructions = 0;
            for (var node : classes.values())
                for (var code : node.methods) {
                    if (code.instructions.size() == 0) continue;
                    instructions += code.instructions.size();
                    if (instructions > 80_000) return false;
                    boolean relevant = serializer(code) || code.name.equals("<init>");
                    for (var instruction : code.instructions)
                        if (instruction instanceof FieldInsnNode field
                                && field.getOpcode() == Opcodes.PUTFIELD
                                && field.desc.equals("I")
                                && field(field) != null) relevant = true;
                    if (!relevant) continue;
                    if (++count > 128 || code.maxLocals > 256 || code.maxStack > 256) return false;
                    var method = new Method(node.name, code);
                    var analyzer =
                            new Analyzer<BasicValue>(new Interpreter(this)) {
                                @Override
                                protected void newControlFlowEdge(int from, int to) {
                                    method.edges.get(from).add(to);
                                }

                                @Override
                                protected boolean newControlFlowExceptionEdge(int from, int to) {
                                    method.edges.get(from).add(to);
                                    return true;
                                }
                            };
                    method.frames = analyzer.analyze(node.name, code);
                    method.collectWrites(this);
                    methods.add(method);
                }
            return true;
        }

        Set<Method> serializers() {
            var signatures = new HashSet<String>();
            var active = new HashSet<Method>();
            for (var method : methods)
                if (serializer(method.code) && signatures.add(method.code.name + method.code.desc))
                    active.add(method);
            var pending = new ArrayDeque<>(active);
            while (!pending.isEmpty()) {
                var method = pending.remove();
                for (int i = 0; i < method.frames.length; i++) {
                    if (!(method.code.instructions.get(i) instanceof MethodInsnNode call)
                            || call.getOpcode() != Opcodes.INVOKESPECIAL
                            || !SERIALIZERS.contains(call.name)) continue;
                    var values = arguments(method.frames[i], call);
                    int tag = tagArgument(call.desc);
                    if (tag < 0
                            || values.size() <= tag + 1
                            || origin(values.getFirst()) != Origin.THIS
                            || origin(values.get(tag + 1)) != Origin.ROOT_TAG) continue;
                    var target = method(call.owner, call.name, call.desc);
                    if (target != null && active.add(target)) pending.add(target);
                }
            }
            return active;
        }

        Method method(String owner, String name, String desc) {
            for (var node = classes.get(owner); node != null; node = classes.get(node.superName))
                for (var method : methods)
                    if (method.owner.equals(node.name)
                            && method.code.name.equals(name)
                            && method.code.desc.equals(desc)) return method;
            return null;
        }

        Field field(FieldInsnNode instruction) {
            for (var node = classes.get(instruction.owner);
                    node != null;
                    node = classes.get(node.superName)) {
                var field = declaredFields.get(node.name).get(instruction.name);
                if (field != null) return field;
            }
            return null;
        }

        Bounds bounds(Field field, String key) {
            Bounds range = null;
            var writes = new LinkedHashMap<Method, List<Write>>();
            for (var method : methods) {
                if (method.code.name.equals("<init>")) continue;
                var values = method.writes.getOrDefault(field, List.of());
                if (values.isEmpty()) continue;
                writes.put(method, values);
                for (var write : values)
                    if (write.value instanceof Bounds found) {
                        if (!write.receiver || range != null && !range.equals(found)) return null;
                        range = found;
                    }
            }
            if (range == null) return null;
            for (var method : methods)
                if (method.code.name.equals("<init>")) {
                    var values = method.writes.getOrDefault(field, List.of());
                    if (method.owner.equals(field.owner)
                            && !range.contains(0)
                            && !method.repaired(values, range)[0]) return null;
                    if (!values.isEmpty()) writes.put(method, values);
                }
            for (var entry : writes.entrySet()) {
                boolean[] repaired = null;
                var method = entry.getKey();
                for (var write : entry.getValue()) {
                    if (!write.receiver) return null;
                    if (contained(range, write.value)
                            || write.value instanceof Integer value && range.contains(value)
                            || serializer(method.code)
                                    && write.value instanceof Read read
                                    && read.key.equals(key)) continue;
                    if (repaired == null) repaired = method.repaired(entry.getValue(), range);
                    if (!repaired[write.index]) return null;
                }
            }
            return range;
        }
    }

    private static final class Method {
        final String owner;
        final MethodNode code;
        final List<Set<Integer>> edges = new ArrayList<>();
        final Map<Field, List<Write>> writes = new HashMap<>();
        Frame<BasicValue>[] frames;

        Method(String owner, MethodNode code) {
            this.owner = owner;
            this.code = code;
            for (int i = 0; i < code.instructions.size(); i++) edges.add(new HashSet<>());
        }

        void collectWrites(Scan scan) {
            for (int i = 0; i < frames.length; i++) {
                if (!(code.instructions.get(i) instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.PUTFIELD
                        || !field.desc.equals("I")) continue;
                var target = scan.field(field);
                var frame = frames[i];
                if (target == null || frame == null || frame.getStackSize() < 2) continue;
                writes.computeIfAbsent(target, ignored -> new ArrayList<>())
                        .add(
                                new Write(
                                        i,
                                        origin(frame.getStack(frame.getStackSize() - 1)),
                                        origin(frame.getStack(frame.getStackSize() - 2))
                                                == Origin.THIS));
            }
        }

        boolean initializingThis(int index, MethodInsnNode call) {
            if (!code.name.equals("<init>")
                    || !call.name.equals("<init>")
                    || call.getOpcode() != Opcodes.INVOKESPECIAL) return false;
            var values = arguments(frames[index], call);
            return !values.isEmpty() && origin(values.getFirst()) == Origin.THIS;
        }

        boolean[] repaired(List<Write> writes, Bounds bounds) {
            int size = frames.length;
            var good = new boolean[size];
            var remaining = new int[size];
            var predecessors = new ArrayList<List<Integer>>(size);
            for (int i = 0; i < size; i++) predecessors.add(new ArrayList<>());
            for (int from = 0; from < size; from++) {
                var instruction = code.instructions.get(from);
                if (instruction instanceof InvokeDynamicInsnNode
                        || instruction instanceof MethodInsnNode call
                                && !clampCall(call)
                                && !initializingThis(from, call)) continue;
                remaining[from] = edges.get(from).size();
                for (int to : edges.get(from)) predecessors.get(to).add(from);
            }
            var pending = new ArrayDeque<Integer>();
            for (var write : writes)
                if (write.receiver
                        && (contained(bounds, write.value)
                                || write.value instanceof Integer value
                                        && bounds.contains(value))) {
                    good[write.index] = true;
                    pending.add(write.index);
                }
            // A node is repaired only after every successor is proved repaired. Exits and
            // unresolved cycles remain false. Opaque calls also stop the proof: normal CFG
            // edges do not represent an unhandled callback exception before the repair.
            while (!pending.isEmpty())
                for (int before : predecessors.get(pending.remove()))
                    if (--remaining[before] == 0 && !good[before]) {
                        good[before] = true;
                        pending.add(before);
                    }
            return good;
        }
    }

    private static boolean clampCall(MethodInsnNode method) {
        return method.getOpcode() == Opcodes.INVOKESTATIC
                && method.owner.equals("net/minecraft/util/Mth")
                && method.name.equals("clamp")
                && method.desc.equals("(III)I");
    }

    private static boolean contained(Bounds bounds, Object value) {
        return value instanceof Bounds stored
                && bounds.contains(stored.min)
                && bounds.contains(stored.max);
    }

    private static boolean serializer(MethodNode method) {
        return SERIALIZERS.contains(method.name) && tagArgument(method.desc) >= 0;
    }

    private static int tagArgument(String descriptor) {
        int found = -1;
        var arguments = Type.getArgumentTypes(descriptor);
        for (int i = 0; i < arguments.length; i++)
            if (arguments[i].getDescriptor().equals("L" + TAG + ";")) {
                if (found >= 0) return -1;
                found = i;
            }
        return found;
    }

    private static List<BasicValue> arguments(Frame<BasicValue> frame, MethodInsnNode method) {
        if (frame == null) return List.of();
        int count =
                Type.getArgumentTypes(method.desc).length
                        + (method.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
        if (frame.getStackSize() < count) return List.of();
        var values = new ArrayList<BasicValue>(count);
        for (int i = frame.getStackSize() - count; i < frame.getStackSize(); i++)
            values.add(frame.getStack(i));
        return values;
    }

    private static Object origin(BasicValue value) {
        return value instanceof Value tracked ? tracked.origin : null;
    }

    private static final class Value extends BasicValue {
        final Object origin;

        Value(Type type, Object origin) {
            super(type);
            this.origin = origin;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Value value
                    && Objects.equals(getType(), value.getType())
                    && Objects.equals(origin, value.origin);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getType(), origin);
        }
    }

    private static final class Interpreter extends BasicInterpreter {
        private final Scan scan;

        Interpreter(Scan scan) {
            super(Opcodes.ASM9);
            this.scan = scan;
        }

        BasicValue wrap(BasicValue value, Object origin) {
            return value == null ? null : new Value(value.getType(), origin);
        }

        @Override
        public BasicValue newValue(Type type) {
            return wrap(super.newValue(type), null);
        }

        @Override
        public BasicValue newParameterValue(boolean instance, int local, Type type) {
            return wrap(
                    super.newParameterValue(instance, local, type),
                    instance && local == 0
                            ? Origin.THIS
                            : type.getDescriptor().equals("L" + TAG + ";")
                                    ? Origin.ROOT_TAG
                                    : null);
        }

        @Override
        public BasicValue newOperation(AbstractInsnNode instruction) throws AnalyzerException {
            int opcode = instruction.getOpcode();
            Object constant =
                    instruction instanceof LdcInsnNode ldc
                            ? ldc.cst
                            : instruction instanceof IntInsnNode integer
                                            && opcode != Opcodes.NEWARRAY
                                    ? integer.operand
                                    : opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5
                                            ? opcode - Opcodes.ICONST_0
                                            : null;
            return wrap(super.newOperation(instruction), constant);
        }

        @Override
        public BasicValue copyOperation(AbstractInsnNode instruction, BasicValue value) {
            return value;
        }

        @Override
        public BasicValue unaryOperation(AbstractInsnNode instruction, BasicValue value)
                throws AnalyzerException {
            Object result = null;
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.desc.equals("I")
                    && origin(value) == Origin.THIS) result = scan.field(field);
            if (instruction.getOpcode() == Opcodes.CHECKCAST) result = origin(value);
            return wrap(super.unaryOperation(instruction, value), result);
        }

        @Override
        public BasicValue binaryOperation(
                AbstractInsnNode instruction, BasicValue left, BasicValue right)
                throws AnalyzerException {
            return wrap(super.binaryOperation(instruction, left, right), null);
        }

        @Override
        public BasicValue naryOperation(
                AbstractInsnNode instruction, List<? extends BasicValue> values)
                throws AnalyzerException {
            Object result = null;
            if (instruction instanceof MethodInsnNode method) {
                if (method.owner.equals(TAG)
                        && method.name.equals("getInt")
                        && method.desc.equals("(Ljava/lang/String;)I")
                        && values.size() == 2
                        && origin(values.get(0)) == Origin.ROOT_TAG
                        && origin(values.get(1)) instanceof String key) result = new Read(key);
                if (clampCall(method)
                        && values.size() == 3
                        && origin(values.get(1)) instanceof Integer min
                        && origin(values.get(2)) instanceof Integer max
                        && min <= max) result = new Bounds(min, max);
            }
            return wrap(super.naryOperation(instruction, values), result);
        }

        @Override
        public BasicValue merge(BasicValue left, BasicValue right) {
            if (left.equals(right)) return left;
            if (Objects.equals(left.getType(), right.getType()))
                return new Value(left.getType(), null);
            return wrap(super.merge(left, right), null);
        }
    }
}
