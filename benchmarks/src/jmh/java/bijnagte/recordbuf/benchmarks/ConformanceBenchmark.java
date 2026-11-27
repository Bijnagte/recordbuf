package bijnagte.recordbuf.benchmarks;

import com.google.protobuf_test_messages.proto3.rec.TestAllTypesProto3;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * JMH benchmarks for the conformance (TestAllTypesProto3) recordbuf record: encode, decode, and
 * interop conversion to/from the protobuf-java reference.
 */
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
@OutputTimeUnit(TimeUnit.SECONDS)
public class ConformanceBenchmark {

  private TestAllTypesProto3 record;
  private byte[] encoded;
  private com.google.protobuf_test_messages.proto3.TestAllTypesProto3 proto;

  @Setup
  public void setup() throws IOException {
    proto =
        com.google.protobuf_test_messages.proto3.TestAllTypesProto3.newBuilder()
            .setOptionalInt32(42)
            .setOptionalString("test")
            .setOptionalBool(true)
            .setOptionalFloat(3.14f)
            .setOptionalDouble(2.718)
            .build();
    record = TestAllTypesProto3.Interop.from(proto);
    encoded = record.toByteArray();
  }

  @Benchmark
  public byte[] encode() throws IOException {
    return record.toByteArray();
  }

  @Benchmark
  public TestAllTypesProto3 decode() throws IOException {
    return TestAllTypesProto3.fromBytes(encoded);
  }

  @Benchmark
  public com.google.protobuf_test_messages.proto3.TestAllTypesProto3 toProto() {
    return TestAllTypesProto3.Interop.to(record);
  }

  @Benchmark
  public TestAllTypesProto3 fromProto() {
    return TestAllTypesProto3.Interop.from(proto);
  }

  @Benchmark
  public byte[] protoEncode() {
    return proto.toByteArray();
  }

  @Benchmark
  public com.google.protobuf_test_messages.proto3.TestAllTypesProto3 protoDecode()
      throws IOException {
    return com.google.protobuf_test_messages.proto3.TestAllTypesProto3.parseFrom(encoded);
  }

  /** Encode path through interop: record → protobuf-java → bytes. */
  @Benchmark
  public byte[] interopEncode() {
    return TestAllTypesProto3.Interop.to(record).toByteArray();
  }

  /** Decode path through interop: bytes → protobuf-java → record. */
  @Benchmark
  public TestAllTypesProto3 interopDecode() throws IOException {
    return TestAllTypesProto3.Interop.from(
        com.google.protobuf_test_messages.proto3.TestAllTypesProto3.parseFrom(encoded));
  }
}
