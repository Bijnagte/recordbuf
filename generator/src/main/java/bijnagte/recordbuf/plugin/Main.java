package bijnagte.recordbuf.plugin;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the {@code protoc-gen-recordbuf} plugin.
 *
 * <p>Wire protocol with {@code protoc} (see <a
 * href="https://protobuf.dev/develop/plugins/">protobuf.dev</a>): on stdin, {@code protoc} writes a
 * single serialized {@link CodeGeneratorRequest} (no length prefix — protobuf self-describes its
 * length on the wire). On stdout, we write a single serialized {@link CodeGeneratorResponse} (also
 * length-prefixed by the protobuf encoding: a tag, a length, then the bytes).
 *
 * <p>Errors are reported via the response {@code error} field (this is what makes {@code protoc
 * --fail-fast=false} possible); only fatal plugin-level failures (e.g. malformed request) log to
 * stderr (slf4j-simple's only output channel) and exit non-zero.
 */
public final class Main {
  private static final Logger LOG = LoggerFactory.getLogger(Main.class);

  private Main() {}

  public static void main(String[] args) throws IOException {
    try {
      byte[] in = System.in.readAllBytes();
      CodeGeneratorRequest request = CodeGeneratorRequest.parseFrom(in);
      CodeGeneratorResponse response = new CodeGenerator().generate(request);
      byte[] out = response.toByteArray();
      System.out.write(out, 0, out.length);
      System.out.flush();
    } catch (InvalidProtocolBufferException e) {
      // Malformed request — fatal: log the failure (message + stack trace) to stderr —
      // slf4j-simple's only output channel, so the stdout protocol stays clean — and exit.
      LOG.error("recordbuf: malformed CodeGeneratorRequest", e);
      System.exit(2);
    } catch (RuntimeException e) {
      // Generation error — report via response so protoc can continue
      // with other plugins/files and surface the error cleanly.
      LOG.debug("recordbuf: generation failed", e);
      CodeGeneratorResponse err =
          CodeGeneratorResponse.newBuilder().setError("recordbuf: " + e.getMessage()).build();
      byte[] out = err.toByteArray();
      System.out.write(out, 0, out.length);
      System.out.flush();
    }
  }
}
