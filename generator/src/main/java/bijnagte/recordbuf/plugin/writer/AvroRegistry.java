package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.plugin.bridge.AvroBridge;
import bijnagte.recordbuf.plugin.bridge.AvroUnsupportedException;
import bijnagte.recordbuf.plugin.model.Enum;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FileTable;
import bijnagte.recordbuf.plugin.model.JavaNames;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.ModelLoader;
import bijnagte.recordbuf.plugin.model.Oneof;
import bijnagte.recordbuf.plugin.model.ProtoFile;
import bijnagte.recordbuf.plugin.types.WellKnownTypes;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The Avro registry for one {@code .avsc} document: the whole reachable type graph of a bridged
 * root message — every reachable record (root, same-file siblings and nested records, imported
 * records followed across files, oneof message members, and map-value messages/enums), plus the
 * per-message Avro <em>name</em> and <em>namespace</em> that the schema tree, the converter, and
 * the specific-record list all read from this single source.
 *
 * <p><b>Namespace:</b> a bridged record's Avro namespace IS the proto package of its declaring
 * file; the document's namespace is the root file's. Imported records therefore appear in the tree
 * with an explicit, different namespace (and their Avro names are unique per namespace, not per
 * document).
 *
 * <p><b>Names:</b> {@code cap(message name) + "Avro"} — the same distinctness convention the v1
 * single-record bridge used, now namespaced instead of relying on the root's position. When two
 * reachable messages share a name in one namespace (a nested record in two parents, two imports
 * from different packages), the name is prefixed with one enclosing segment (the parent record's
 * name, or the package's last segment for a top-level message); a collision that survives that
 * prefix fails loud.
 *
 * <p><b>WKTs:</b> a {@code google.protobuf.*} reference that the Avro mapping covers contributes no
 * named definition (its fragment is inlined at field level); a well-known type with no static
 * schema (a dynamic WKT such as {@code Struct}/{@code Any}) fails loud — it cannot be bridged.
 *
 * <p><b>Unknown fields:</b> every bridged record carries a trailing {@code unknownFields} field (a
 * structural twin of the recordbuf carrier); the single entry record it references has the fixed
 * name {@link #UNKNOWN_FIELDS_ENTRY} (disambiguated, and failing loud, only if a reachable name
 * would occupy it).
 *
 * <p><b>Unknown references:</b> a message reference that resolves to nothing declared in the
 * request fails loud (the v1 fail-loud contract, now applied to the graph as a whole): the bridge
 * cannot emit a dangling schema reference.
 */
public final class AvroRegistry {

  /** The fixed name of the unknown-field entry record every bridged record references. */
  public static final String UNKNOWN_FIELDS_ENTRY = "RecordbufUnknownField";

  /**
   * The fixed name of the shared oneof-value wrapper record: {@code {tag: int, value: union}} — the
   * KTD5 case carrier for scalar/enum oneof members (message members ride their own specific
   * records in the oneof field's union).
   */
  public static final String ONEOF_VALUE_ENTRY = "RecordbufOneofValue";

  private final String documentNamespace;
  private final String entryRecordName;
  private final Map<String, String> nameByFqn;
  private final Map<String, String> namespaceByFqn;
  private final Map<String, Message> messageByFqn;
  private final Map<Message, String> fqnByMessage;
  private final Set<String> enumFqns;
  private final Map<String, String> recordPackageByFqn;

  private AvroRegistry(
      String documentNamespace,
      String entryRecordName,
      Map<String, String> nameByFqn,
      Map<String, String> namespaceByFqn,
      Map<String, Message> messageByFqn,
      Map<Message, String> fqnByMessage,
      Set<String> enumFqns,
      Map<String, String> recordPackageByFqn) {
    this.documentNamespace = documentNamespace;
    this.entryRecordName = entryRecordName;
    this.nameByFqn = nameByFqn;
    this.namespaceByFqn = namespaceByFqn;
    this.messageByFqn = messageByFqn;
    this.fqnByMessage = fqnByMessage;
    this.enumFqns = enumFqns;
    this.recordPackageByFqn = recordPackageByFqn;
  }

  /**
   * Build the registry for {@code root}: walk the whole reachable graph (nested records, oneof
   * message members, map-value references, and imported records reached through the request's
   * {@code table}) and fix every reachable message's Avro name + namespace.
   *
   * @param file the root message's declaring file
   * @param root the bridged root message (a top-level message of {@code file})
   * @param table the request's type-resolution table, or null for single-file input; imported
   *     references are followed through it
   * @throws AvroUnsupportedException when a message reference resolves to nothing generatable
   */
  public static AvroRegistry build(ProtoFile file, Message root, @Nullable FileTable table) {
    String documentNamespace = AvroSchemaGenerator.avroNamespace(file.protoPackage());

    // Phase 1: the reachable graph, breadth-first in first-seen order.
    Map<String, Entry> entries = new LinkedHashMap<>();
    Map<String, ProtoFile> loadedFiles = new LinkedHashMap<>();
    Map<ProtoFile, Map<String, Message>> messageIndexes = new LinkedHashMap<>();
    Map<ProtoFile, Set<String>> enumIndexes = new LinkedHashMap<>();
    Deque<Entry> queue = new ArrayDeque<>();
    queue.add(new Entry(file, root, List.of(root.name())));
    while (!queue.isEmpty()) {
      Entry entry = queue.poll();
      if (entries.containsKey(entry.fqn())) continue;
      entries.put(entry.fqn(), entry);
      index(entry.file(), messageIndexes);
      indexEnums(entry.file(), enumIndexes);
      for (Field f : entry.message().fields()) {
        resolveReference(f, entry, entries, queue, table, loadedFiles, messageIndexes, enumIndexes);
        // A map's value is itself a type reference (a message/enum value resolves to an FQN).
        if (f.isMap() && f.mapValue() != null) {
          resolveTokenReference(
              f.mapValue(), entry, entries, queue, table, loadedFiles, messageIndexes, enumIndexes);
        }
      }
      for (Oneof oneof : entry.message().oneofs()) {
        for (Field member : oneof.members()) {
          resolveReference(
              member, entry, entries, queue, table, loadedFiles, messageIndexes, enumIndexes);
        }
      }
      for (Message nested : entry.message().nestedMessages()) {
        queue.add(new Entry(entry.file(), nested, newChained(entry.chain(), nested.name())));
      }
    }

    // Phase 2: names — cap(short name) + "Avro", prefixing on within-namespace collisions.
    Map<String, List<String>> groups = new LinkedHashMap<>();
    for (Entry entry : entries.values()) {
      groups.computeIfAbsent(groupKey(entry, false), k -> new ArrayList<>()).add(entry.fqn());
    }
    Map<String, String> claimedNames = new LinkedHashMap<>();
    Map<String, String> nameByFqn = new LinkedHashMap<>();
    Map<String, String> namespaceByFqn = new LinkedHashMap<>();
    Map<String, Message> messageByFqn = new LinkedHashMap<>();
    Map<Message, String> fqnByMessage = new LinkedHashMap<>();
    for (Map.Entry<String, Entry> e : entries.entrySet()) {
      Entry entry = e.getValue();
      boolean collides = groups.getOrDefault(groupKey(entry, false), List.of()).size() > 1;
      String prefix = collisionPrefix(entry, collides);
      String name = prefix + AvroSchemaGenerator.specificRecordName(entry.message().name());
      String key = namespaceKey(entry, name);
      if (claimedNames.containsKey(key)) {
        throw new AvroUnsupportedException(
            name,
            "two reachable messages name-collide in Avro namespace '"
                + entry.namespace()
                + "'; the Avro bridge cannot disambiguate them");
      }
      claimedNames.put(key, e.getKey());
      nameByFqn.put(e.getKey(), name);
      namespaceByFqn.put(e.getKey(), entry.namespace());
      messageByFqn.put(e.getKey(), entry.message());
      fqnByMessage.put(entry.message(), e.getKey());
    }
    Map<String, String> recordPackageByFqn = new LinkedHashMap<>();
    for (Map.Entry<String, Entry> e : entries.entrySet()) {
      recordPackageByFqn.put(e.getKey(), AvroBridge.recordPkg(e.getValue().file()));
    }

    // The shared unknown-field entry record: fixed name, disambiguated/failing loud only if a
    // reachable name would occupy it (reachable names carry the "Avro" suffix, so in practice it
    // never does).
    String entryName = UNKNOWN_FIELDS_ENTRY;
    String entryKey = namespaceKeyRaw(documentNamespace, entryName);
    if (claimedNames.containsKey(entryKey)) {
      String segment = lastSegment(documentNamespace);
      if (segment.isEmpty()) {
        throw new AvroUnsupportedException(
            UNKNOWN_FIELDS_ENTRY,
            "a reachable message occupies the unknown-field entry record's name; the Avro bridge "
                + "cannot disambiguate");
      }
      entryName = segment + UNKNOWN_FIELDS_ENTRY;
      if (claimedNames.containsKey(namespaceKeyRaw(documentNamespace, entryName))) {
        throw new AvroUnsupportedException(
            entryName,
            "a reachable message occupies the unknown-field entry record's name; the Avro bridge "
                + "cannot disambiguate");
      }
    }

    return new AvroRegistry(
        documentNamespace,
        entryName,
        nameByFqn,
        namespaceByFqn,
        messageByFqn,
        fqnByMessage,
        collectEnums(entries, enumIndexes),
        recordPackageByFqn);
  }

  // ---- resolution ----

  private record Entry(ProtoFile file, Message message, List<String> chain) {
    String fqn() {
      return join(file.protoPackage(), chain);
    }

    String namespace() {
      return file.protoPackage();
    }
  }

  /**
   * Resolve one typed field's reference. A WKT reference contributes no definition (avro-mapped) or
   * fails loud (dynamic); a message reference is walked; an enum reference is only indexed (it maps
   * to a closed string and defines nothing — a dangling one is the record side's problem, matching
   * the loader's leniency, so it never fails the bridge).
   */
  private static void resolveReference(
      Field f,
      Entry owner,
      Map<String, Entry> entries,
      Deque<Entry> queue,
      @Nullable FileTable table,
      Map<String, ProtoFile> loadedFiles,
      Map<ProtoFile, Map<String, Message>> messageIndexes,
      Map<ProtoFile, Set<String>> enumIndexes) {
    String ref = normalize(f.typeUrl());
    if (f.kind() == FieldKind.ENUM) {
      enumIndexes.computeIfAbsent(owner.file(), AvroRegistry::indexEnums).add(ref);
      return;
    }
    if (f.kind() != FieldKind.MESSAGE) return;
    resolveTokenReference(
        ref, owner, entries, queue, table, loadedFiles, messageIndexes, enumIndexes);
  }

  private static void resolveTokenReference(
      String token,
      Entry owner,
      Map<String, Entry> entries,
      Deque<Entry> queue,
      @Nullable FileTable table,
      Map<String, ProtoFile> loadedFiles,
      Map<ProtoFile, Map<String, Message>> messageIndexes,
      Map<ProtoFile, Set<String>> enumIndexes) {
    String ref = normalize(token);
    if (ref.isEmpty()) {
      throw new AvroUnsupportedException(
          token, "unresolvable type reference; the Avro bridge fails loud rather than dangle");
    }
    if (AvroSchemaGenerator.scalarTokens().contains(ref)) {
      // A scalar map value (int32, string, ...): the Avro map carries its value directly —
      // nothing to walk.
      return;
    }
    if (AvroSchemaGenerator.wktFqns().contains(ref)) return; // avro-mapped WKT: inline fragment
    if (enumIndexes.getOrDefault(owner.file(), Set.of()).contains(ref)) {
      return; // an enum map value maps to string — the enum is already indexed for its file
    }
    if (WellKnownTypes.resolve(ref) != null) {
      throw new AvroUnsupportedException(
          ref,
          "well-known type with no static Avro schema (dynamic WKTs are not generatable); the "
              + "Avro bridge fails loud");
    }
    String fqn = ref;
    Message target = messageIndexes.getOrDefault(owner.file(), Map.of()).get(fqn);
    if (target != null) {
      enqueue(owner.file(), fqn, target, entries, queue);
      return;
    }
    if (table != null) {
      String fileName = table.fileNameOf(fqn);
      if (fileName != null) {
        FileDescriptorProto fdp = table.byName(fileName);
        if (fdp != null) {
          ProtoFile decl =
              loadedFiles.computeIfAbsent(
                  fileName, k -> new ModelLoader(table, table.javaPackage(fdp)).load(fdp));
          Message imported = index(decl, messageIndexes).get(fqn);
          if (imported != null) {
            enqueue(decl, fqn, imported, entries, queue);
            return;
          }
        }
      }
    }
    throw new AvroUnsupportedException(
        ref,
        "message reference resolves to nothing declared in this request; the Avro bridge cannot "
            + "emit a dangling schema reference");
  }

  private static void enqueue(
      ProtoFile file, String fqn, Message target, Map<String, Entry> entries, Deque<Entry> queue) {
    if (entries.containsKey(fqn)) return;
    List<String> chain = chainFromFqn(file.protoPackage(), fqn);
    queue.add(new Entry(file, target, chain));
  }

  /**
   * The proto chain for a type whose FQN is known: the FQN with the declaring file's package
   * stripped (a top-level message has a one-segment chain; a nested one keeps its nesting).
   */
  private static List<String> chainFromFqn(String protoPackage, String fqn) {
    String rest =
        protoPackage.isEmpty()
            ? fqn
            : fqn.startsWith(protoPackage + ".") ? fqn.substring(protoPackage.length() + 1) : fqn;
    String[] parts = rest.split("\\.");
    return List.of(parts);
  }

  // ---- per-file indexes ----

  private static Map<String, Message> index(
      ProtoFile file, Map<ProtoFile, Map<String, Message>> indexes) {
    return indexes.computeIfAbsent(file, AvroRegistry::messageIndex);
  }

  private static Map<String, Message> messageIndex(ProtoFile pf) {
    Map<String, Message> idx = new LinkedHashMap<>();
    for (Message m : pf.messages()) {
      indexMessage(pf.protoPackage(), m, List.of(m.name()), idx);
    }
    return idx;
  }

  private static void indexMessage(
      String protoPackage, Message m, List<String> chain, Map<String, Message> idx) {
    idx.put(join(protoPackage, chain), m);
    for (Message nested : m.nestedMessages()) {
      indexMessage(protoPackage, nested, newChained(chain, nested.name()), idx);
    }
  }

  private static Set<String> indexEnums(ProtoFile pf) {
    Set<String> idx = new LinkedHashSet<>();
    for (Enum e : pf.enums()) {
      idx.add(join(pf.protoPackage(), List.of(e.name())));
    }
    for (Message m : pf.messages()) {
      indexMessageEnums(pf.protoPackage(), m, List.of(m.name()), idx);
    }
    return idx;
  }

  private static Set<String> indexEnums(ProtoFile pf, Map<ProtoFile, Set<String>> indexes) {
    return indexes.computeIfAbsent(pf, AvroRegistry::indexEnums);
  }

  private static void indexMessageEnums(
      String protoPackage, Message m, List<String> chain, Set<String> idx) {
    for (Enum e : m.nestedEnums()) {
      idx.add(join(protoPackage, newChained(chain, e.name())));
    }
    for (Message nested : m.nestedMessages()) {
      indexMessageEnums(protoPackage, nested, newChained(chain, nested.name()), idx);
    }
  }

  private static Set<String> collectEnums(
      Map<String, Entry> entries, Map<ProtoFile, Set<String>> enumIndexes) {
    Set<String> all = new LinkedHashSet<>();
    for (Entry entry : entries.values()) {
      all.addAll(enumIndexes.get(entry.file()));
    }
    return all;
  }

  // ---- naming helpers ----

  private static String groupKey(Entry entry, boolean prefixed) {
    String name = AvroSchemaGenerator.specificRecordName(entry.message().name());
    if (prefixed) {
      name = collisionPrefix(entry, true) + name;
    }
    return namespaceKey(entry, name);
  }

  private static String collisionPrefix(Entry entry, boolean apply) {
    if (!apply) return "";
    if (entry.chain().size() > 1) {
      return JavaNames.cap(entry.chain().get(entry.chain().size() - 2));
    }
    return lastSegment(entry.namespace());
  }

  private static String namespaceKey(Entry entry, String name) {
    return namespaceKeyRaw(entry.namespace(), name);
  }

  private static String namespaceKeyRaw(String namespace, String name) {
    return namespace + "\u0000" + name;
  }

  private static String lastSegment(String nameOrPackage) {
    if (nameOrPackage == null || nameOrPackage.isEmpty()) return "";
    int dot = nameOrPackage.lastIndexOf('.');
    return dot < 0 ? nameOrPackage : nameOrPackage.substring(dot + 1);
  }

  private static String join(String protoPackage, List<String> chain) {
    String joined = String.join(".", chain);
    return protoPackage.isEmpty() ? joined : protoPackage + "." + joined;
  }

  private static <T> List<T> newChained(List<T> chain, T tail) {
    List<T> out = new ArrayList<>(chain);
    out.add(tail);
    return List.copyOf(out);
  }

  private static String normalize(@Nullable String ref) {
    if (ref == null) return "";
    String s = ref.trim();
    return s.startsWith(".") ? s.substring(1) : s;
  }

  // ---- accessors ----

  /** The document's namespace: the root file's Avro namespace (the bare proto package). */
  public String documentNamespace() {
    return documentNamespace;
  }

  /** The name of the shared unknown-field entry record for this document. */
  public String entryRecordName() {
    return entryRecordName;
  }

  /** Every reachable message's proto FQN, in first-seen (breadth-first) order. */
  public Set<String> protoFqns() {
    return new LinkedHashSet<>(nameByFqn.keySet());
  }

  /** The Avro record name for a reachable message's FQN, or null if not part of this graph. */
  public @Nullable String nameOf(String protoFqn) {
    return nameByFqn.get(protoFqn);
  }

  /** The declaring file's proto package (the record's Avro namespace) for a reachable FQN. */
  public @Nullable String namespaceOf(String protoFqn) {
    return namespaceByFqn.get(protoFqn);
  }

  /** The reachable message object for a proto FQN, or null if not part of this graph. */
  public @Nullable Message messageOf(String protoFqn) {
    return messageByFqn.get(protoFqn);
  }

  /** The proto FQN of a reachable message (the one this graph walked), or null. */
  public @Nullable String fqnOf(Message m) {
    return fqnByMessage.get(m);
  }

  /** The Avro record name for a reachable message, or null if it is not part of this graph. */
  public @Nullable String nameOf(Message m) {
    String fqn = fqnByMessage.get(m);
    return fqn == null ? null : nameByFqn.get(fqn);
  }

  /** The declaring file's proto package (the record's Avro namespace) for a reachable message. */
  public @Nullable String namespaceOfMessage(Message m) {
    String fqn = fqnByMessage.get(m);
    return fqn == null ? null : namespaceByFqn.get(fqn);
  }

  /**
   * The record's Java package (the {@code .rec} suffixed package) for a reachable FQN — the home of
   * its generated record and its converter, for the converter's cross-references.
   */
  public @Nullable String recordPackageOf(String protoFqn) {
    return recordPackageByFqn.get(protoFqn);
  }

  /**
   * The map pair-entry record name for a map field: the owning field's name capitalized with {@code
   * Entry}, plus the {@code Avro} suffix (matching the per-message {@code *Avro} twins so the
   * {@code SpecificCompiler} output is consistently named and excluded from Error Prone). A map
   * with a string key uses Avro's built-in map and defines no pair record.
   */
  public static String mapEntryRecordName(Field f) {
    return JavaNames.cap(f.name()) + "EntryAvro";
  }

  /** Whether the token names an enum this graph indexed (enum references map to a string). */
  public boolean isEnum(String token) {
    return enumFqns.contains(normalize(token));
  }
}
