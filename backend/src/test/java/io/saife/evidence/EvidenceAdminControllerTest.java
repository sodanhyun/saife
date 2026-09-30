package io.saife.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 최종 리뷰 F17 — 시드 내보내기 위치는 작업 디렉터리 아래로만 */
class EvidenceAdminControllerTest {
    private static final Path BASE = Path.of("").toAbsolutePath().normalize();

    @Test
    void 기본값과_하위_경로는_허용한다() {
        assertThat(EvidenceAdminController.resolveExportDir(null))
                .isEqualTo(BASE.resolve("backend/src/main/resources/seed").normalize());
        assertThat(EvidenceAdminController.resolveExportDir("build/seed-out")).isEqualTo(BASE.resolve("build/seed-out"));
    }

    @Test
    void 작업_디렉터리_밖은_거부한다() {
        assertThatThrownBy(() -> EvidenceAdminController.resolveExportDir("../outside"))
                .isInstanceOf(IllegalArgumentException.class);
        String outsideAbs = BASE.getRoot().resolve("saife-export-outside").toString();
        assertThatThrownBy(() -> EvidenceAdminController.resolveExportDir(outsideAbs))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvidenceAdminController.resolveExportDir("a/../../x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
