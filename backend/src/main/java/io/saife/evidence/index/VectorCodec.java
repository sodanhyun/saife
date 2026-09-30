package io.saife.evidence.index;

import io.saife.evidence.search.SearchPolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * 768차원 임베딩 벡터 ↔ base64 인코더. 두 가지 레이아웃을 지원한다.
 *
 * <p><b>float32(LE)</b> — {@link #encode(float[])}. 비트 손실이 없어 회귀 테스트·비-시드
 * 경로에서 계속 쓴다.
 *
 * <p><b>Q8(선형 양자화, A4 hotfix H2)</b> — {@link #encodeQ8(float[])}. 인덱스가 스펙
 * 추정(1.58만)보다 큰 약 4만 청크로 커지면서 float32 base64 시드가 git 파일 크기 한도를
 * 넘게 됐다. 시드 파일의 벡터만 벡터별 min/max로 8비트 양자화해 크기를 1/4로 줄인다
 * (DB의 {@code vector(768)} 컬럼 자체는 그대로 float다 — 정밀도 손실은 시드 파일 안에서만
 * 발생하고, 로더가 DB에 넣을 때 다시 float로 복원한다).
 *
 * <p>{@link #decode(String)}는 두 레이아웃을 바이트 길이로 자동 판별한다 — float32는
 * 항상 4의 배수(768차원이면 3072바이트)이고 Q8은 항상 778바이트라서 겹치지 않는다.
 */
public final class VectorCodec {

    /** Q8 레이아웃 매직 바이트 — ASCII 'Q' */
    private static final int Q8_MAGIC = 0x51;
    private static final byte Q8_VERSION = 0x01;
    /** 헤더 = 매직(1) + 버전(1) + min(4) + max(4) */
    private static final int Q8_HEADER_BYTES = 10;
    /** 10(헤더) + 768(uint8 성분) = 778바이트 */
    private static final int Q8_ENCODED_BYTES = Q8_HEADER_BYTES + SearchPolicy.EMBEDDING_DIMENSIONS;

    private VectorCodec() {
    }

    /** float[] → base64(float32 LE). null 입력은 null을 돌려준다(parent 청크는 embedding이 없다) */
    public static String encode(float[] v) {
        if (v == null) {
            return null;
        }
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) {
            b.putFloat(f);
        }
        return Base64.getEncoder().encodeToString(b.array());
    }

    /**
     * float[] → base64(Q8 양자화). 레이아웃(리틀 엔디안): 매직 바이트 {@code 0x51}('Q') +
     * 버전 {@code 0x01} + float32 {@code min} + float32 {@code max} + 768 × uint8
     * ({@code q = round((x-min)/(max-min)*255)}, {@code max==min}이면 전부 0). 총
     * 778바이트 → base64 약 1,040자 — float32 인코딩(4096자) 대비 시드 크기 1/4.
     *
     * <p>null 입력은 null을 돌려준다(parent 청크는 embedding이 없다). 반올림·부동소수점
     * 오차로 양자값이 [0,255] 밖으로 미세하게 벗어나는 경우를 방어적으로 clamp한다.
     */
    public static String encodeQ8(float[] v) {
        if (v == null) {
            return null;
        }
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (float f : v) {
            if (f < min) {
                min = f;
            }
            if (f > max) {
                max = f;
            }
        }
        float range = max - min;
        ByteBuffer b = ByteBuffer.allocate(Q8_HEADER_BYTES + v.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) Q8_MAGIC);
        b.put(Q8_VERSION);
        b.putFloat(min);
        b.putFloat(max);
        for (float f : v) {
            int q = (range == 0f) ? 0 : Math.round((f - min) / range * 255f);
            q = Math.max(0, Math.min(255, q));
            b.put((byte) q);
        }
        return Base64.getEncoder().encodeToString(b.array());
    }

    /**
     * base64 → float[]. null/빈 문자열은 null을 돌려준다. 디코딩된 바이트 길이로 float32
     * 레이아웃(3072바이트)과 Q8 레이아웃(778바이트)을 자동 판별한다.
     *
     * <p>손상되거나 다른 차원으로 생성된 시드 파일이 조용히 잘못된 길이의 벡터를
     * 만들어 pgvector insert에서야 실패하는 걸 막기 위해, 여기서 바로
     * {@link SearchPolicy#EMBEDDING_DIMENSIONS}(768)와 맞는지 검증한다.
     */
    public static float[] decode(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        byte[] bytes = Base64.getDecoder().decode(s);
        if (bytes.length == Q8_ENCODED_BYTES) {
            return decodeQ8(bytes);
        }
        int floatCount = bytes.length / 4;
        if (bytes.length % 4 != 0 || floatCount != SearchPolicy.EMBEDDING_DIMENSIONS) {
            throw new IllegalArgumentException("[SEED] 벡터 길이 불일치: expected "
                    + SearchPolicy.EMBEDDING_DIMENSIONS + " floats, got " + floatCount);
        }
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[floatCount];
        for (int i = 0; i < v.length; i++) {
            v[i] = b.getFloat();
        }
        return v;
    }

    /** Q8 레이아웃 디코딩. 매직·버전이 어긋나면 손상된 시드로 보고 즉시 예외를 던진다 */
    private static float[] decodeQ8(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        byte magic = b.get();
        byte version = b.get();
        if (magic != (byte) Q8_MAGIC || version != Q8_VERSION) {
            throw new IllegalArgumentException(
                    "[SEED] Q8 벡터 매직 불일치: magic=0x%02X version=%d".formatted(magic, version));
        }
        float min = b.getFloat();
        float max = b.getFloat();
        float range = max - min;
        float[] v = new float[SearchPolicy.EMBEDDING_DIMENSIONS];
        for (int i = 0; i < v.length; i++) {
            int q = b.get() & 0xFF;
            v[i] = (range == 0f) ? min : min + (q / 255f) * range;
        }
        return v;
    }
}
