package bijnagte.recordbuf.fixture.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.fixture.identity.rec.Holder;
import bijnagte.recordbuf.fixture.identity.rec.Kind;
import bijnagte.recordbuf.fixture.identity.rec.Node;
import bijnagte.recordbuf.fixture.identity.rec.Part;
import bijnagte.recordbuf.fixture.identity.rec.Shelter;
import bijnagte.recordbuf.fixture.identityfar.rec.Far;
import bijnagte.recordbuf.runtime.Unwrapable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Identity-subtype coverage (oneof-identity plan, U7). A oneof case whose type is a generated
 * message or enum is the sealed interface's subtype itself; a case that cannot be (a scalar, a case
 * from another package, or the enclosing message's own self-reference) keeps its <Cap>Value
 * wrapper, which implements {@code Unwrapable<T>} for the member's type. The sealed interface
 * itself is a bare marker: identity subtypes are read as-is, wrapper subtypes through {@code
 * instanceof Unwrapable<?>}. Covers the multi-interface case (Part is a case of four oneofs), the
 * self-referential wrapper (Node's child case), the cross-package wrapper (Far), and the scalar
 * wrappers, plus wire round-trips and parity with the protobuf-java reference.
 */
class IdentitySubtypeTest {

  private static final JsonMapper MAPPER = new JsonMapper();

  private static final Part PART = Part.builder().name("p").build();
  private static final Far FAR = Far.builder().text("t").build();

  @Test
  void messageCaseIsItsOwnSubtype() {
    // Part is a case of Holder.item, Node.next, Shelter.slot, and Holder.Inner.pick: one instance
    // satisfies all four sealed interfaces, no wrapper in between. The Object reference keeps the
    // instanceofs runtime checks instead of compile-time tautologies (BadInstanceof).
    Object part = PART;
    assertTrue(part instanceof Holder.Item);
    assertTrue(part instanceof Node.Next);
    assertTrue(part instanceof Shelter.Slot);
    assertTrue(part instanceof Holder.Inner.Pick);
    assertFalse(part instanceof Unwrapable<?>);
  }

  @Test
  void enumCaseIsItsOwnSubtype() {
    Object alpha = Kind.ALPHA;
    assertTrue(alpha instanceof Holder.Item);
    assertTrue(alpha instanceof Node.Next);
    assertFalse(alpha instanceof Shelter.Slot);
    assertFalse(alpha instanceof Unwrapable<?>);
  }

  @Test
  void selfReferentialCaseKeepsItsWrapper() {
    // Node is a case of its own oneof, but a direct self-reference is never identity: the member
    // keeps its ChildValue wrapper, which unwraps to the nested Node.
    Node inner = Node.builder().next(PART).build();
    Node leaf = Node.builder().next(new Node.Next.ChildValue(inner)).build();

    Node.Next next = leaf.next();
    assertTrue(next instanceof Node.Next.ChildValue);
    assertSame(inner, ((Node.Next.ChildValue) next).value());
  }

  @Test
  void scalarAndCrossPackageCasesKeepWrappers() {
    Holder.Item label = Holder.builder().item(new Holder.Item.LabelValue("label")).build().item();
    assertTrue(label instanceof Unwrapable<?>);
    assertEquals("label", ((Unwrapable<?>) label).value());

    Holder holder = Holder.builder().item(new Holder.Item.FarValue(FAR)).build();
    assertTrue(holder.item() instanceof Holder.Item.FarValue);
    assertEquals(FAR, ((Holder.Item.FarValue) holder.item()).value());

    Node leaf = Node.builder().next(new Node.Next.LeafValue("leaf")).build();
    assertTrue(leaf.next() instanceof Node.Next.LeafValue);
    assertEquals("leaf", ((Node.Next.LeafValue) leaf.next()).value());
  }

  @Test
  void wireRoundTripsKeepIdentity() throws Exception {
    Holder holder = Holder.builder().item(new Holder.Item.FarValue(FAR)).build();
    assertEquals(holder, Holder.fromBytes(holder.toByteArray()));

    Node inner = Node.builder().next(PART).build();
    Node node = Node.builder().next(new Node.Next.ChildValue(inner)).build();
    assertEquals(node, Node.fromBytes(node.toByteArray()));

    Shelter shelter = Shelter.builder().slot(PART).build();
    assertEquals(shelter, Shelter.fromBytes(shelter.toByteArray()));

    Holder.Inner innerPick = Holder.Inner.builder().pick(PART).build();
    assertEquals(innerPick, Holder.Inner.fromBytes(innerPick.toByteArray()));
  }

  @Test
  void encodedWireMatchesProtobufJavaReference() throws Exception {
    // A cross-package wrapper case on the wire is identical to what protobuf-java emits: the
    // wrapper exists only in the record surface, never on the wire.
    var reference =
        bijnagte.recordbuf.fixture.identity.Holder.newBuilder()
            .setFar(bijnagte.recordbuf.fixture.identityfar.Far.newBuilder().setText("t").build())
            .build();
    Holder record = Holder.builder().item(new Holder.Item.FarValue(FAR)).build();
    assertArrayEquals(reference.toByteArray(), record.toByteArray());

    var referenceHolder =
        bijnagte.recordbuf.fixture.identity.Holder.newBuilder()
            .setPart(bijnagte.recordbuf.fixture.identity.Part.newBuilder().setName("p").build())
            .build();
    Holder identityHolder = Holder.builder().item(PART).build();
    assertArrayEquals(referenceHolder.toByteArray(), identityHolder.toByteArray());
  }

  @Test
  void jacksonRoundTripsIdentityOneof() throws Exception {
    Node inner = Node.builder().next(PART).build();
    Node node = Node.builder().next(new Node.Next.ChildValue(inner)).build();
    assertEquals(node, MAPPER.readValue(MAPPER.writeValueAsString(node), Node.class));

    Holder holder = Holder.builder().item(new Holder.Item.FarValue(FAR)).build();
    assertEquals(holder, MAPPER.readValue(MAPPER.writeValueAsString(holder), Holder.class));
  }
}
