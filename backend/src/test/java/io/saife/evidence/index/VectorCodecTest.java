package io.saife.evidence.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.Random;
import org.junit.jupiter.api.Test;

class VectorCodecTest {

    /**
     * 768개 float32 값이 encode→decode 후 비트 단위로 그대로 살아남는지 검증한다.
     * float 비교는 -0.0f == 0.0f처럼 값이 같으면 통과해버려 비트 손상을 놓칠 수 있으므로
     * {@link Float#floatToIntBits(float)}로 비트 표현을 직접 비교한다.
     * NaN은 pgvector가 저장을 거부하므로 제외하고, 대신 극단값(최소·최대·부호·무한대)을 섞는다.
     */
    @Test
    void 라운드트립() {
        float[] v = new float[768];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) Math.sin(i) * 0.01f;
        }
        v[0] = Float.MIN_VALUE;
        v[1] = -Float.MAX_VALUE;
        v[2] = Float.MAX_VALUE;
        v[3] = 0f;
        v[4] = -0f;
        v[5] = Float.POSITIVE_INFINITY;
        v[6] = Float.NEGATIVE_INFINITY;

        String encoded = VectorCodec.encode(v);
        float[] decoded = VectorCodec.decode(encoded);

        assertThat(decoded).hasSize(768);
        for (int i = 0; i < v.length; i++) {
            assertThat(Float.floatToIntBits(decoded[i]))
                    .as("index %d 비트 불일치: 원본=%s 복원=%s", i, v[i], decoded[i])
                    .isEqualTo(Float.floatToIntBits(v[i]));
        }
        assertThat(encoded.length()).isEqualTo(4096);   // 768*4 bytes → base64 4096자
    }

    @Test
    void null과_빈문자열() {
        assertThat(VectorCodec.encode(null)).isNull();
        assertThat(VectorCodec.decode(null)).isNull();
        assertThat(VectorCodec.decode("")).isNull();
    }

    /**
     * 767개짜리 인코딩(4바이트 배수지만 차원이 틀림)과 10바이트짜리 base64(4바이트 배수도 아님)
     * 둘 다 예외로 걸러야 한다 — 손상된 시드가 조용히 잘못된 차원의 벡터를 만들면
     * pgvector insert에서야 실패해 원인 추적이 어려워진다.
     */
    @Test
    void 길이가_틀리면_예외() {
        float[] wrongDimension = new float[767];
        assertThatThrownBy(() -> VectorCodec.decode(VectorCodec.encode(wrongDimension)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[SEED] 벡터 길이 불일치")
                .hasMessageContaining("767");

        String tenBytesBase64 = Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).array());
        assertThatThrownBy(() -> VectorCodec.decode(tenBytesBase64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[SEED] 벡터 길이 불일치");
    }

    /**
     * A4 hotfix H2: Q8 양자화 라운드트립. 무작위 768차원 벡터 20개에 대해
     * ① 성분별 오차가 격자 간격({@code (max-min)/255}) + 1e-6 이내인지,
     * ② 원본과 복원 벡터의 코사인 유사도가 0.999 이상인지 검증한다.
     * 측정된 최댓값(maxComponentError)·최솟값(minCosine)을 표준출력에 남겨
     * 실측치를 보고서에 그대로 옮길 수 있게 한다.
     */
    @Test
    void Q8_라운드트립_오차와_코사인유사도() {
        Random rnd = new Random(42);
        float maxComponentError = 0f;
        double minCosine = Double.POSITIVE_INFINITY;

        for (int trial = 0; trial < 20; trial++) {
            float[] v = new float[768];
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) (rnd.nextDouble() * 2 - 1); // [-1, 1)
            }

            String encoded = VectorCodec.encodeQ8(v);
            float[] decoded = VectorCodec.decode(encoded);
            assertThat(decoded).hasSize(768);

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
            float tolerance = (max - min) / 255f + 1e-6f;

            double dot = 0;
            double normV = 0;
            double normD = 0;
            for (int i = 0; i < v.length; i++) {
                float err = Math.abs(decoded[i] - v[i]);
                assertThat(err).as("trial %d index %d 성분 오차", trial, i).isLessThanOrEqualTo(tolerance);
                maxComponentError = Math.max(maxComponentError, err);
                dot += (double) v[i] * decoded[i];
                normV += (double) v[i] * v[i];
                normD += (double) decoded[i] * decoded[i];
            }
            double cosine = dot / (Math.sqrt(normV) * Math.sqrt(normD));
            assertThat(cosine).as("trial %d 코사인 유사도", trial).isGreaterThanOrEqualTo(0.999);
            minCosine = Math.min(minCosine, cosine);
        }

        System.out.printf("[VectorCodecTest] Q8 실측: maxComponentError=%.6f minCosine=%.6f%n",
                maxComponentError, minCosine);
    }

    /** 768차원 Q8 인코딩은 헤더 10바이트 + 성분 768바이트 = 정확히 778바이트여야 한다 */
    @Test
    void Q8_인코딩_길이는_778바이트() {
        float[] v = new float[768];
        for (int i = 0; i < v.length; i++) {
            v[i] = i * 0.001f;
        }
        byte[] decodedBytes = Base64.getDecoder().decode(VectorCodec.encodeQ8(v));
        assertThat(decodedBytes.length).isEqualTo(778);
    }

    /** 778바이트라도 매직 바이트가 훼손되면 손상된 시드로 간주해 예외를 던져야 한다 */
    @Test
    void Q8_매직_불일치시_예외() {
        float[] v = new float[768];
        byte[] bytes = Base64.getDecoder().decode(VectorCodec.encodeQ8(v));
        bytes[0] = 0x00; // 매직('Q'=0x51) 훼손
        String corrupted = Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> VectorCodec.decode(corrupted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[SEED] Q8 벡터 매직 불일치");
    }
}
