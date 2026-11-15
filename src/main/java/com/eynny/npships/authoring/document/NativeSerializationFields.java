package com.eynny.npships.authoring.document;

import net.minecraft.nbt.*;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.io.IOException;
import java.util.*;

/**
 * Read-only evidence from the installed serializer's bytecode. Keys are followed from an NBT
 * parameter through native getters and typed consumers, never inferred from spelling. No native
 * reader, setter, constructor, tick or world callback is executed by this analysis.
 */
public final class NativeSerializationFields {
    private NativeSerializationFields() {}

    public enum Kind {
        BYTE,
        SHORT,
        INT,
        LONG,
        FLOAT,
        DOUBLE,
        BYTE_ARRAY,
        INT_ARRAY,
        LONG_ARRAY,
        STRING,
        COMPOUND,
        LIST,
        ITEM,
        BLOCK_STATE
    }

    public record Field(List<String> path, Kind kind, int elementType, String evidence) {
        public Field {
            path = List.copyOf(path);
        }
    }

    public record Schema(List<Field> fields, List<String> limitations) {
        public Schema {
            fields = List.copyOf(fields);
            limitations = List.copyOf(limitations);
        }

        public Set<List<String>> itemPaths() {
            var out = new HashSet<List<String>>();
            for (var f : fields) if (f.kind == Kind.ITEM) out.add(f.path);
            return Set.copyOf(out);
        }

        /** Help-only shapes. Never passed back to a native load/apply/save operation. */
        public void expand(CompoundTag reference) {
            for (var f : fields) install(reference, f, 0);
        }
    }

    private static final String TAG = "net/minecraft/nbt/CompoundTag";
    private static final String LIST = "net/minecraft/nbt/ListTag";
    private static final String ITEM = "net/minecraft/world/item/ItemStack";
    private static final ClassValue<Schema> CACHE =
            new ClassValue<>() {
                @Override
                protected Schema computeValue(Class<?> type) {
                    return new Scan(type).run();
                }
            };

    public static Schema of(Class<?> type) {
        return CACHE.get(type);
    }

    private static void install(CompoundTag root, Field f, int index) {
        if (index >= f.path.size() || index > 24) return;
        String key = f.path.get(index);
        if (key.equals("[]")) return;
        boolean last = index + 1 == f.path.size();
        if (last) {
            Tag old = root.get(key);
            if (f.kind == Kind.ITEM || f.kind == Kind.BLOCK_STATE) {
                if (old == null || old instanceof CompoundTag) {
                    var sample = old instanceof CompoundTag c ? c : new CompoundTag();
                    typedExample(sample, f.kind);
                    root.put(key, sample);
                }
            } else if (old == null) {
                Tag sample =
                        switch (f.kind) {
                            case BYTE -> ByteTag.ZERO;
                            case SHORT -> ShortTag.valueOf((short) 0);
                            case INT -> IntTag.valueOf(0);
                            case LONG -> LongTag.valueOf(0);
                            case FLOAT -> FloatTag.valueOf(0);
                            case DOUBLE -> DoubleTag.valueOf(0);
                            case BYTE_ARRAY -> new ByteArrayTag(new byte[0]);
                            case INT_ARRAY -> new IntArrayTag(new int[0]);
                            case LONG_ARRAY -> new LongArrayTag(new long[0]);
                            case STRING -> StringTag.valueOf("");
                            case COMPOUND -> new CompoundTag();
                            case LIST -> new ListTag();
                            default -> throw new IllegalStateException();
                        };
                root.put(key, sample);
            }
            if (f.kind == Kind.LIST && root.get(key) instanceof ListTag list && list.isEmpty()) {
                Tag element = listElement(f.elementType);
                if (element != null) list.add(element);
            }
            return;
        }
        if (f.path.get(index + 1).equals("[]")) {
            if (!root.contains(key)) root.put(key, new ListTag());
            if (!(root.get(key) instanceof ListTag entries)) return;
            if (entries.isEmpty() && index + 2 < f.path.size()) entries.add(new CompoundTag());
            if (entries.isEmpty()
                    && index + 2 == f.path.size()
                    && (f.kind == Kind.ITEM || f.kind == Kind.BLOCK_STATE)) {
                var sample = new CompoundTag();
                typedExample(sample, f.kind);
                entries.add(sample);
            }
            for (var entry : entries)
                if (entry instanceof CompoundTag c) {
                    if (index + 2 == f.path.size()
                            && (f.kind == Kind.ITEM || f.kind == Kind.BLOCK_STATE))
                        typedExample(c, f.kind);
                    else install(c, f, index + 2);
                }
        } else {
            if (!root.contains(key)) root.put(key, new CompoundTag());
            if (root.get(key) instanceof CompoundTag c) install(c, f, index + 1);
        }
    }

    private static Tag listElement(int type) {
        return switch (type) {
            case Tag.TAG_BYTE -> ByteTag.ZERO;
            case Tag.TAG_SHORT -> ShortTag.valueOf((short) 0);
            case Tag.TAG_INT -> IntTag.valueOf(0);
            case Tag.TAG_LONG -> LongTag.valueOf(0);
            case Tag.TAG_FLOAT -> FloatTag.valueOf(0);
            case Tag.TAG_DOUBLE -> DoubleTag.valueOf(0);
            case Tag.TAG_STRING -> StringTag.valueOf("");
            case Tag.TAG_BYTE_ARRAY -> new ByteArrayTag(new byte[0]);
            case Tag.TAG_INT_ARRAY -> new IntArrayTag(new int[0]);
            case Tag.TAG_LONG_ARRAY -> new LongArrayTag(new long[0]);
            case Tag.TAG_COMPOUND -> new CompoundTag();
            case Tag.TAG_LIST -> new ListTag();
            default -> null; // Unknown element type remains unknown.
        };
    }

    private static void typedExample(CompoundTag sample, Kind kind) {
        if (kind == Kind.ITEM) {
            if (!sample.contains("id")) sample.putString("id", "minecraft:stone");
            if (!sample.contains("count")) sample.putInt("count", 1);
        } else if (!sample.contains("Name")) {
            sample.putString("Name", "minecraft:stone");
        }
    }

    private static final Set<String> SERIALIZER_ENTRIES =
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

    private static final class Scan {
        private final Class<?> type;
        private final Map<String, ClassNode> classes = new HashMap<>();
        private final Map<String, List<Field>> summaries = new HashMap<>();
        private final Set<String> visiting = new HashSet<>();
        private final Map<List<String>, Field> fields = new LinkedHashMap<>();
        private final Set<List<String>> conflicts = new HashSet<>();
        private final LinkedHashSet<String> gaps = new LinkedHashSet<>();
        private int methods, instructions;

        Scan(Class<?> type) {
            this.type = type;
        }

        Schema run() {
            var entries = new HashSet<String>();
            int parents = 0;
            for (Class<?> at = type; at != null && parents++ < 20; at = at.getSuperclass()) {
                var node = code(at.getName().replace('.', '/'));
                if (node == null) continue;
                for (var method : node.methods) {
                    if ((method.access & Opcodes.ACC_SYNTHETIC) != 0
                            || tagArgument(method.desc) < 0
                            || !SERIALIZER_ENTRIES.contains(method.name)) continue;
                    // An overridden entry is reached only through an explicit super call.
                    if (!entries.add(method.name + method.desc)) continue;
                    for (var field : method(node.name, method, 0, true)) merge(field);
                }
            }
            return new Schema(new ArrayList<>(fields.values()), new ArrayList<>(gaps));
        }

        void merge(Field f) {
            if (!fields.containsKey(f.path) && fields.size() >= 512) {
                gap("Field analysis budget reached");
                return;
            }
            if (conflicts.contains(f.path)) return;
            var old = fields.get(f.path);
            if (old == null
                    || old.kind == f.kind
                    || old.kind == Kind.COMPOUND
                            && (f.kind == Kind.ITEM || f.kind == Kind.BLOCK_STATE)) {
                fields.put(f.path, f);
            } else if (!((old.kind == Kind.ITEM || old.kind == Kind.BLOCK_STATE)
                    && f.kind == Kind.COMPOUND)) {
                fields.remove(f.path);
                conflicts.add(f.path);
                gap("Conflicting native reads: " + String.join(".", f.path));
            }
        }

        void gap(String value) {
            if (gaps.size() < 16) gaps.add(value);
        }

        ClassNode code(String name) {
            if (classes.containsKey(name)) return classes.get(name);
            if (classes.size() >= 48) {
                gap("Class analysis budget reached");
                return null;
            }
            ClassNode value = null;
            try (var stream = resource(name, type.getClassLoader())) {
                if (stream != null) {
                    var raw = stream.readNBytes(1_048_577);
                    if (raw.length <= 1_048_576) {
                        value = new ClassNode();
                        new ClassReader(raw)
                                .accept(value, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    } else gap("Native class exceeds analysis budget");
                } else gap("Native bytecode unavailable: " + name);
            } catch (IOException | RuntimeException | LinkageError ex) {
                gap("Native bytecode unavailable: " + name);
            }
            classes.put(name, value);
            return value;
        }

        List<Field> method(String owner, MethodNode method, int depth, boolean rootReceiver) {
            String id = owner + "." + method.name + method.desc + ":" + rootReceiver;
            if (summaries.containsKey(id)) return summaries.get(id);
            if (depth > 8
                    || methods >= 128
                    || instructions + method.instructions.size() > 80_000
                    || !visiting.add(id)) {
                gap("Helper/branch analysis budget reached");
                return List.of();
            }
            methods++;
            instructions += method.instructions.size();
            var out = new LinkedHashMap<List<String>, Field>();
            try {
                new Analyzer<BasicValue>(new Reader(this, out, id, depth, rootReceiver))
                        .analyze(owner, method);
            } catch (AnalyzerException | RuntimeException unsupported) {
                gap("Unresolved native control flow: " + owner + "." + method.name);
            }
            visiting.remove(id);
            var result = List.copyOf(out.values());
            summaries.put(id, result);
            return result;
        }

        List<Field> helper(
                String owner,
                String name,
                String desc,
                int depth,
                boolean rootReceiver,
                boolean virtual) {
            // Only a proven current receiver may dispatch through the concrete serializer.
            // Super calls and calls on another object retain their declared implementation.
            if (rootReceiver && virtual && rootOwner(owner)) {
                int parents = 0;
                for (Class<?> at = type; at != null && parents++ < 20; at = at.getSuperclass()) {
                    var concrete = code(at.getName().replace('.', '/'));
                    if (concrete == null) continue;
                    for (var method : concrete.methods)
                        if (method.name.equals(name)
                                && method.desc.equals(desc)
                                && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT))
                                        == 0) return method(concrete.name, method, depth, true);
                }
            }
            var node = code(owner);
            if (node == null) return List.of();
            // Explicit super entry frames serialize the same owner, not a nested payload.
            // Total class/method/instruction limits still bound the complete hierarchy.
            int helperDepth =
                    rootReceiver
                                    && !virtual
                                    && rootOwner(owner)
                                    && SERIALIZER_ENTRIES.contains(name)
                            ? 0
                            : depth;
            // Bytecode may name the immediate parent even when that parent inherits the
            // implementation. Resolve that declared call through its real superclass chain.
            for (int parents = 0;
                    node != null && parents++ < 20;
                    node = node.superName == null ? null : code(node.superName))
                for (var method : node.methods)
                    if (method.name.equals(name) && method.desc.equals(desc))
                        return method(node.name, method, helperDepth, rootReceiver);
            return List.of();
        }

        boolean rootOwner(String owner) {
            try {
                return Class.forName(owner.replace('/', '.'), false, type.getClassLoader())
                        .isAssignableFrom(type);
            } catch (ClassNotFoundException | LinkageError unavailable) {
                return false;
            }
        }
    }

    static java.io.InputStream resource(String owner, ClassLoader loader) {
        try {
            // Class resources are module-owned. Do not ask one mod's Module for another mod's
            // bytes, and never initialise a class just to inspect its serialization constants.
            var declared = Class.forName(owner.replace('/', '.'), false, loader);
            return declared.getResourceAsStream("/" + owner + ".class");
        } catch (ClassNotFoundException | LinkageError unavailable) {
            return null;
        }
    }

    public static Map<Integer, String> keyboardKeys() {
        return Keys.VALUES;
    }

    private static final class Keys {
        static final Map<Integer, String> VALUES = read();

        static Map<Integer, String> read() {
            var result = new TreeMap<Integer, String>();
            try (var stream =
                    resource(
                            "org/lwjgl/glfw/GLFW",
                            NativeSerializationFields.class.getClassLoader())) {
                if (stream != null)
                    new ClassReader(stream)
                            .accept(
                                    new ClassVisitor(Opcodes.ASM9) {
                                        @Override
                                        public FieldVisitor visitField(
                                                int access,
                                                String name,
                                                String descriptor,
                                                String signature,
                                                Object value) {
                                            if (name.startsWith("GLFW_KEY_")
                                                    && !name.equals("GLFW_KEY_LAST")
                                                    && value instanceof Integer code
                                                    && code >= 0) {
                                                String label =
                                                        name.substring(9)
                                                                .replace('_', ' ')
                                                                .toLowerCase(Locale.ROOT);
                                                label =
                                                        label.length() == 1
                                                                ? label.toUpperCase(Locale.ROOT)
                                                                : Character.toUpperCase(
                                                                                label.charAt(0))
                                                                        + label.substring(1);
                                                result.put(code, label);
                                            }
                                            return null;
                                        }
                                    },
                                    ClassReader.SKIP_CODE
                                            | ClassReader.SKIP_DEBUG
                                            | ClassReader.SKIP_FRAMES);
            } catch (IOException | RuntimeException | LinkageError unavailable) {
                return Map.of();
            }
            return Map.copyOf(result);
        }
    }

    private static int tagArgument(String desc) {
        var args = Type.getArgumentTypes(desc);
        int index = -1;
        for (int i = 0; i < args.length; i++)
            if (args[i].getDescriptor().equals("L" + TAG + ";")) {
                if (index >= 0) return -1;
                index = i;
            }
        return index;
    }

    private static final class Value extends BasicValue {
        final List<String> path;
        final Object constant;
        final Kind role;

        Value(Type type, List<String> path, Object constant, Kind role) {
            super(type);
            this.path = path;
            this.constant = constant;
            this.role = role;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Value v
                    && Objects.equals(getType(), v.getType())
                    && Objects.equals(path, v.path)
                    && Objects.equals(constant, v.constant)
                    && role == v.role;
        }

        @Override
        public int hashCode() {
            return Objects.hash(getType(), path, constant, role);
        }
    }

    private enum Receiver {
        ROOT
    }

    private static final class Reader extends BasicInterpreter {
        final Scan scan;
        final Map<List<String>, Field> out;
        final Set<List<String>> conflicts = new HashSet<>();
        final String evidence;
        final int depth;
        final boolean rootReceiver;

        Reader(
                Scan scan,
                Map<List<String>, Field> out,
                String evidence,
                int depth,
                boolean rootReceiver) {
            super(Opcodes.ASM9);
            this.scan = scan;
            this.out = out;
            this.evidence = evidence;
            this.depth = depth;
            this.rootReceiver = rootReceiver;
        }

        BasicValue wrap(BasicValue value, List<String> path, Object constant, Kind role) {
            return value == null ? null : new Value(value.getType(), path, constant, role);
        }

        @Override
        public BasicValue newParameterValue(boolean instance, int local, Type type) {
            return wrap(
                    super.newParameterValue(instance, local, type),
                    type.getDescriptor().equals("L" + TAG + ";") ? List.of() : null,
                    instance && local == 0 && rootReceiver ? Receiver.ROOT : null,
                    null);
        }

        @Override
        public BasicValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
            var base = super.newOperation(insn);
            Object constant =
                    insn instanceof LdcInsnNode ldc
                            ? ldc.cst
                            : insn instanceof IntInsnNode n
                                    ? n.operand
                                    : insn.getOpcode() >= Opcodes.ICONST_M1
                                                    && insn.getOpcode() <= Opcodes.ICONST_5
                                            ? insn.getOpcode() - Opcodes.ICONST_0
                                            : null;
            return wrap(
                    base,
                    null,
                    constant,
                    insn instanceof FieldInsnNode f
                                    && f.owner.equals(ITEM)
                                    && f.desc.equals("L" + ITEM + ";")
                            ? Kind.ITEM
                            : null);
        }

        @Override
        public BasicValue copyOperation(AbstractInsnNode insn, BasicValue value) {
            return value;
        }

        @Override
        public BasicValue unaryOperation(AbstractInsnNode insn, BasicValue value)
                throws AnalyzerException {
            if (insn.getOpcode() == Opcodes.CHECKCAST && value instanceof Value v)
                return new Value(
                        Type.getObjectType(((TypeInsnNode) insn).desc), v.path, v.constant, v.role);
            return wrap(super.unaryOperation(insn, value), null, null, null);
        }

        void record(List<String> path, Kind kind, int element) {
            if (path == null || path.isEmpty() || path.size() > 24 || out.size() >= 512) return;
            var key = List.copyOf(path);
            if (conflicts.contains(key)) return;
            var next = new Field(key, kind, element, evidence);
            var old = out.get(key);
            if (old == null
                    || old.kind == kind
                    || old.kind == Kind.COMPOUND && (kind == Kind.ITEM || kind == Kind.BLOCK_STATE))
                out.put(key, next);
            else if (!((old.kind == Kind.ITEM || old.kind == Kind.BLOCK_STATE)
                    && kind == Kind.COMPOUND)) {
                out.remove(key);
                conflicts.add(key);
                scan.gap("Conflicting native reads: " + String.join(".", key));
            }
        }

        List<String> path(BasicValue v) {
            return v instanceof Value e ? e.path : null;
        }

        Object constant(BasicValue v) {
            return v instanceof Value e ? e.constant : null;
        }

        List<String> child(List<String> path, String key) {
            if (path == null) return null;
            var next = new ArrayList<>(path);
            next.add(key);
            return List.copyOf(next);
        }

        @Override
        public BasicValue naryOperation(AbstractInsnNode insn, List<? extends BasicValue> values)
                throws AnalyzerException {
            var base = super.naryOperation(insn, values);
            if (!(insn instanceof MethodInsnNode m)) return wrap(base, null, null, null);
            int receiver = m.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;

            if (m.owner.equals(TAG)
                    && receiver == 1
                    && values.size() >= 2
                    && constant(values.get(1)) instanceof String key) {
                var p = child(path(values.getFirst()), key);
                Kind kind =
                        switch (m.name) {
                            case "getBoolean", "getByte" -> Kind.BYTE;
                            case "getShort" -> Kind.SHORT;
                            case "getInt" -> Kind.INT;
                            case "getLong" -> Kind.LONG;
                            case "getFloat" -> Kind.FLOAT;
                            case "getDouble" -> Kind.DOUBLE;
                            case "getByteArray" -> Kind.BYTE_ARRAY;
                            case "getIntArray", "getUUID" -> Kind.INT_ARRAY;
                            case "getLongArray" -> Kind.LONG_ARRAY;
                            case "getString" -> Kind.STRING;
                            case "getCompound" -> Kind.COMPOUND;
                            case "getList" -> Kind.LIST;
                            default -> null;
                        };
                if (kind != null) {
                    int element =
                            m.name.equals("getList")
                                            && values.size() > 2
                                            && constant(values.get(2)) instanceof Integer n
                                    ? n
                                    : 0;
                    record(p, kind, element);
                    return wrap(base, kind == Kind.LIST ? child(p, "[]") : p, null, null);
                }
                if (m.name.equals("put")
                        && values.size() == 3
                        && values.get(2) instanceof Value val
                        && val.role != null) record(p, val.role, 0);
            }
            if (m.owner.equals(LIST)
                    && receiver == 1
                    && List.of("getCompound", "get", "iterator", "stream").contains(m.name))
                return wrap(base, path(values.getFirst()), null, null);
            if (m.owner.equals("java/util/Iterator") && m.name.equals("next"))
                return wrap(base, path(values.getFirst()), null, null);
            boolean typedItem = m.owner.equals(ITEM);
            if (typedItem) {
                for (var value : values) if (path(value) != null) record(path(value), Kind.ITEM, 0);
                return wrap(base, null, null, Kind.ITEM);
            }
            if (m.owner.equals("net/minecraft/nbt/NbtUtils") && m.name.equals("readBlockState")) {
                for (var value : values)
                    if (path(value) != null) record(path(value), Kind.BLOCK_STATE, 0);
                return wrap(base, null, null, Kind.BLOCK_STATE);
            }
            if (m.owner.equals("net/minecraft/nbt/NbtUtils") && m.name.equals("writeBlockState"))
                return wrap(base, null, null, Kind.BLOCK_STATE);
            int index = tagArgument(m.desc);
            if (index >= 0 && index + receiver < values.size()) {
                var prefix = path(values.get(index + receiver));
                if (prefix != null && !m.owner.equals(TAG)) {
                    for (var field :
                            scan.helper(
                                    m.owner,
                                    m.name,
                                    m.desc,
                                    depth + 1,
                                    receiver == 1 && constant(values.getFirst()) == Receiver.ROOT,
                                    m.getOpcode() == Opcodes.INVOKEVIRTUAL
                                            || m.getOpcode() == Opcodes.INVOKEINTERFACE)) {
                        var p = new ArrayList<>(prefix);
                        p.addAll(field.path);
                        record(p, field.kind, field.elementType);
                    }
                }
            }
            return wrap(base, null, null, null);
        }

        @Override
        public BasicValue merge(BasicValue left, BasicValue right) {
            if (left.equals(right)) return left;
            return wrap(
                    super.merge(left, right),
                    Objects.equals(path(left), path(right)) ? path(left) : null,
                    null,
                    null);
        }
    }
}
