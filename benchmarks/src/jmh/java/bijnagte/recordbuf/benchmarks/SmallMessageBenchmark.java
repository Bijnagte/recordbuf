package bijnagte.recordbuf.benchmarks;

import bijnagte.recordbuf.benchmarks.rec.BenchmarkSmall;
import bijnagte.recordbuf.runtime.UnknownFields;
import java.io.IOException;
import java.util.List;
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
 * JMH benchmarks for the small (3-field) recordbuf record: encode, decode, and interop conversion
 * to/from the protobuf-java reference.
 */
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
@OutputTimeUnit(TimeUnit.SECONDS)
public class SmallMessageBenchmark {

  private BenchmarkSmall record;
  private byte[] encoded;
  private bijnagte.recordbuf.benchmarks.BenchmarkSmall proto;

  @Setup
  public void setup() throws IOException {
    record = new BenchmarkSmall(42, "test", List.of("a", "b", "c"), null, UnknownFields.empty());
    encoded = record.toByteArray();
    proto = BenchmarkSmall.Interop.to(record);
  }

  @Benchmark
  public byte[] encode() throws IOException {
    return record.toByteArray();
  }

  @Benchmark
  public BenchmarkSmall decode() throws IOException {
    return BenchmarkSmall.fromBytes(encoded);
  }

  @Benchmark
  public bijnagte.recordbuf.benchmarks.BenchmarkSmall toProto() {
    return BenchmarkSmall.Interop.to(record);
  }

  @Benchmark
  public BenchmarkSmall fromProto() {
    return BenchmarkSmall.Interop.from(proto);
  }

  @Benchmark
  public byte[] protoEncode() {
    return proto.toByteArray();
  }

  @Benchmark
  public bijnagte.recordbuf.benchmarks.BenchmarkSmall protoDecode() throws IOException {
    return bijnagte.recordbuf.benchmarks.BenchmarkSmall.parseFrom(encoded);
  }

  /** Encode path through interop: record → protobuf-java → bytes. */
  @Benchmark
  public byte[] interopEncode() {
    return BenchmarkSmall.Interop.to(record).toByteArray();
  }

  /** Decode path through interop: bytes → protobuf-java → record. */
  @Benchmark
  public BenchmarkSmall interopDecode() throws IOException {
    return BenchmarkSmall.Interop.from(
        bijnagte.recordbuf.benchmarks.BenchmarkSmall.parseFrom(encoded));
  }
}
