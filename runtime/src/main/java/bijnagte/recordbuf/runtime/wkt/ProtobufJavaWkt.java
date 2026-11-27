package bijnagte.recordbuf.runtime.wkt;

import bijnagte.recordbuf.runtime.Bytes;
import bijnagte.recordbuf.runtime.Empty;
import bijnagte.recordbuf.runtime.FieldMask;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Converts the natively mapped well-known types between their protobuf-java messages and the
 * record's native Java types, in both directions. A generated Interop converter calls one method
 * per element or field instead of inlining the conversion.
 *
 * <p>{@code Timestamp} and {@code Duration} map to {@link Instant} and {@link Duration}; the
 * protobuf {@code Duration} sign rule (seconds and nanos carry the same sign) is applied on the way
 * out. {@code FieldMask} and {@code Empty} map to the runtime {@link FieldMask} and {@link Empty}.
 * Each scalar wrapper ({@code *Value}) maps to its boxed Java value, with {@code UInt32Value} and
 * {@code UInt64Value} carrying the signed bit pattern; {@code BytesValue} maps to {@link Bytes}.
 *
 * <p>The protobuf-to-Java wrapper methods and the Java-to-protobuf wrapper methods are null-in,
 * null-out: a {@code null} wrapper or value converts to {@code null}.
 */
public final class ProtobufJavaWkt {

  private ProtobufJavaWkt() {}

  /**
   * Converts a {@code google.protobuf.Timestamp} to an {@link Instant}.
   *
   * @param timestamp the protobuf timestamp
   * @return the instant at the same seconds and nanos
   */
  public static Instant toInstant(Timestamp timestamp) {
    return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
  }

  /**
   * Converts an {@link Instant} to a {@code google.protobuf.Timestamp}.
   *
   * @param instant the instant
   * @return the protobuf timestamp at the same seconds and nanos
   */
  public static Timestamp fromInstant(Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  /**
   * Converts a {@code google.protobuf.Duration} to a {@link Duration}.
   *
   * @param duration the protobuf duration, whose seconds and nanos share a sign
   * @return the duration with the same length
   */
  public static Duration toDuration(com.google.protobuf.Duration duration) {
    return Duration.ofSeconds(duration.getSeconds(), duration.getNanos());
  }

  /**
   * Converts a {@link Duration} to a {@code google.protobuf.Duration}. The result follows
   * protobuf's sign rule: a negative duration with a fraction is shifted one second toward zero, so
   * {@code -1.5s} becomes {@code {-1, -500000000}} rather than the floor pair {@code {-2,
   * 500000000}}.
   *
   * @param duration the duration
   * @return the protobuf duration with the same length
   */
  public static com.google.protobuf.Duration fromDuration(Duration duration) {
    long seconds = duration.getSeconds();
    int nanos = duration.getNano();
    if (seconds < 0 && nanos > 0) {
      seconds += 1;
      nanos -= 1_000_000_000;
    }
    return com.google.protobuf.Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build();
  }

  /**
   * Converts a {@code google.protobuf.FieldMask} to a {@link FieldMask}. The runtime mask
   * normalizes its paths, so they come back lowercased and de-duplicated, in first-seen order.
   *
   * @param mask the protobuf field mask
   * @return the normalized runtime field mask
   */
  public static FieldMask toFieldMask(com.google.protobuf.FieldMask mask) {
    return FieldMask.of(mask.getPathsList());
  }

  /**
   * Converts a {@link FieldMask} to a {@code google.protobuf.FieldMask}, with its canonical paths
   * in their normalized order.
   *
   * @param mask the runtime field mask
   * @return the protobuf field mask
   */
  public static com.google.protobuf.FieldMask fromFieldMask(FieldMask mask) {
    return com.google.protobuf.FieldMask.newBuilder().addAllPaths(mask.paths()).build();
  }

  /**
   * Converts a {@code google.protobuf.Empty} to the runtime {@link Empty}. Every protobuf {@code
   * Empty} carries no data, so the result is always {@link Empty#INSTANCE}.
   *
   * @param empty the protobuf empty message
   * @return the shared runtime empty value
   */
  public static Empty toEmpty(com.google.protobuf.Empty empty) {
    return Empty.INSTANCE;
  }

  /**
   * Converts the runtime {@link Empty} to a {@code google.protobuf.Empty}.
   *
   * @param empty the runtime empty value
   * @return the protobuf empty message
   */
  public static com.google.protobuf.Empty fromEmpty(Empty empty) {
    return com.google.protobuf.Empty.getDefaultInstance();
  }

  /**
   * Converts a {@code google.protobuf.BoolValue} to its boolean value.
   *
   * @param wrapper the wrapper, or {@code null}
   * @return the wrapped value, or {@code null} for a {@code null} wrapper
   */
  public static @Nullable Boolean toBoolean(@Nullable BoolValue wrapper) {
    return wrapper == null ? null : wrapper.getValue();
  }

  /**
   * Wraps a boolean in a {@code google.protobuf.BoolValue}.
   *
   * @param value the value, or {@code null}
   * @return the wrapper, or {@code null} for a {@code null} value
   */
  public static @Nullable BoolValue boolValue(@Nullable Boolean value) {
    return value == null ? null : BoolValue.newBuilder().setValue(value).build();
  }

  /**
   * Converts an {@code Int32Value} to its {@code int} value.
   *
   * @param wrapper the wrapper, or {@code null}
   * @return the wrapped value, or {@code null} for a {@code null} wrapper
   */
  public static @Nullable Integer toInt32(@Nullable Int32Value wrapper) {
    return wrapper == null ? null : wrapper.getValue();
  }

  /**
   * Wraps an {@code int} in an {@code Int32Value}.
   *
   * @param value the value, or {@code null}
   * @return the wrapper, or {@code null} for a {@code null} value
   */
  public static @Nullable Int32Value int32Value(@Nullable Integer value) {
    return value == null ? null : Int32Value.newBuilder().setValue(value).build();
  }

  /**
   * Converts a {@code UInt32Value} to its bit pattern as a signed {@code int}, so a value above
   * {@link Integer#MAX_VALUE} comes back negative.
   *
   * @param wrapper the wrapper, or {@code null}
   * @return the wrapped bit pattern, or {@code null} for a {@code null} wrapper
   */
  public static @Nullable Integer toUint32(@Nullable UInt32Value wrapper) {
    return wrapper == null ? null : wrapper.getValue();
  }

  /**
   * Wraps an {@code int} bit pattern in a {@code UInt32Value}; a negative {@code int} is the
   * unsigned value above {@link Integer#MAX_VALUE}.
   *
   * @param value the bit pattern, or {@code null}
   * @return the wrapper, or {@code null} for a {@code null} value
   */
  public static @Nullable UInt32Value uint32Value(@Nullable Integer value) {
    return value == null ? null : UInt32Value.newBuilder().setValue(value).build();
  }

  /**
   * Converts an {@code Int64Value} to its {@code long} value.
   *
   * @param wrapper the wrapper, or {@code null}
   * @return the wrapped value, or {@code null} for a {@code null} wrapper
   */
  public static @Nullable Long toInt64(@Nullable Int64Value wrapper) {
    return wrapper == null ? null : wrapper.getValue();
  }

  /**
   * Wraps a {@code long} in an {@code Int64Value}.
   *
   * @param value the value, or {@code null}
   * @return the wrapper, or {@code null} for a {@code null} value
   */
  public static @Nullable Int64Value int64Value(@Nullable Long value) {
    return value == null ? null : Int64Value.newBuilder().setValue(value).build();
  }

  /**
   * Converts a {@code UInt64Value} to its bit pattern as a signed {@code long}, so a value above
   * {@link Long#MAX_VALUE} comes back negative.
   *
   * @param wrapper the wrapper, or {@code null}
   * @return the wrapped bit pattern, or {@code null} for a {@code null} wrapper
   */
  public static @Nullable Long toUint64(@Nullable UInt64Value wrapper) {
    return wrapper == null ? null : wrapper.getValue();
  }

  /**
   * Wraps a {@code long} bit pattern in a {@code UInt64Value}; a negative {@code long} is the
   * unsigned value above {@link Long#MAX_VALUE}.
   *
   * @param value the bit pattern, or {@code null}
   * @return the wrapper, or {@code null} for a {@code null} value
   */
  public static @Nullable UInt64Value uint64Value(@Nullable Long value) {
    return value == null ? null : UInt64Value.newBuilder().setValue(value).build();
  }

  /**
   * Converts a {@code FloatValue} to its {@code float} value.
   *
   * @param wrapper the wrapper, or {@code null}
   * @return the wrapped value, or {@code null} for a {@code null} wrapper
   */
  public static @Nullable Float toFloat(@Nullable FloatValue wrapper) {
    return wrapper == null ? null : wrapper.getValue();
  }

  /**
   * Wraps a {@code float} in a {@code FloatValue}.
   *
   * @param value the value, or {@code null}
   * @return the wrapper, or {@code null} for a {@code null} value
   */
  public static @Nullable FloatValue floatValue(@Nullable Float value) {
    return value == null ? null : FloatValue.newBuilder().setValue(value).build();
  }

  /**
   * Converts a {@code DoubleValue} to its {@code double} value.
   *
   * @param wrapper the wrapper, or {@code null}
   * @return the wrapped value, or {@code null} for a {@code null} wrapper
   */
  public static @Nullable Double toDouble(@Nullable DoubleValue wrapper) {
    return wrapper == null ? null : wrapper.getValue();
  }

  /**
   * Wraps a {@code double} in a {@code DoubleValue}.
   *
   * @param value the value, or {@code null}
   * @return the wrapper, or {@code null} for a {@code null} value
   */
  public static @Nullable DoubleValue doubleValue(@Nullable Double value) {
    return value == null ? null : DoubleValue.newBuilder().setValue(value).build();
  }

  /**
   * Converts a {@code StringValue} to its {@link String} value.
   *
   * @param wrapper the wrapper, or {@code null}
   * @return the wrapped value, or {@code null} for a {@code null} wrapper
   */
  public static @Nullable String toStringValue(@Nullable StringValue wrapper) {
    return wrapper == null ? null : wrapper.getValue();
  }

  /**
   * Wraps a {@link String} in a {@code StringValue}.
   *
   * @param value the value, or {@code null}
   * @return the wrapper, or {@code null} for a {@code null} value
   */
  public static @Nullable StringValue stringValue(@Nullable String value) {
    return value == null ? null : StringValue.newBuilder().setValue(value).build();
  }

  /**
   * Converts a {@code BytesValue} to its {@link Bytes} value.
   *
   * @param wrapper the wrapper, or {@code null}
   * @return the wrapped bytes, or {@code null} for a {@code null} wrapper
   */
  public static @Nullable Bytes toBytes(@Nullable BytesValue wrapper) {
    return wrapper == null ? null : Bytes.of(wrapper.getValue().toByteArray());
  }

  /**
   * Wraps a {@link Bytes} value in a {@code BytesValue}.
   *
   * @param value the bytes, or {@code null}
   * @return the wrapper, or {@code null} for a {@code null} value
   */
  public static @Nullable BytesValue bytesValue(@Nullable Bytes value) {
    return value == null
        ? null
        : BytesValue.newBuilder().setValue(ByteString.copyFrom(value.toByteArray())).build();
  }
}
