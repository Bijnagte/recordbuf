package bijnagte.recordbuf.fixture.golden;

import bijnagte.recordbuf.fixture.golden.format.ProtobufBinaryFormat;
import bijnagte.recordbuf.fixture.i32.rec.Sample;
import java.util.List;

/**
 * The {@code _adapters/int32-min} case in protobuf binary only, registered so that a real {@code
 * seedGoldens} run walks at least one case whose golden already exists and reports it as skipped.
 */
public final class AdapterGoldenCases implements GoldenCaseSource {

  private final ProtobufBinaryFormat protobufBinary = new ProtobufBinaryFormat();

  @Override
  public List<GoldenFormat> formats() {
    return List.of(protobufBinary);
  }

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        GoldenCase.builder("_adapters", "int32-min", Sample.builder().a(0x8000_0000).build())
            .lossless(protobufBinary)
            .build(formats()));
  }
}
