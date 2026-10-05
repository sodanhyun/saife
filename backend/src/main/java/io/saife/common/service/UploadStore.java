package io.saife.common.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** 현장 사진 저장소. 날짜 폴더 아래 무작위 이름으로 저장하고 경로 문자열을 돌려준다 */
@Component
public class UploadStore {

    @Value("${saife.upload-dir:./data/uploads}")
    private String uploadDir;

    public String store(byte[] bytes, String originalName) throws IOException {
        Path dir = Paths.get(uploadDir, LocalDate.now().toString());
        Files.createDirectories(dir);
        Path target = dir.resolve(UUID.randomUUID().toString().replace("-", "") + extensionOf(originalName));
        Files.write(target, bytes);
        return target.toString().replace('\\', '/');
    }

    /** 저장된 사진. 업로드 폴더 밖 경로나 없는 파일은 빈 값 */
    public Optional<Path> resolve(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) return Optional.empty();
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path p = Paths.get(storedPath).toAbsolutePath().normalize();
        if (!p.startsWith(root) || !Files.isRegularFile(p)) return Optional.empty();
        return Optional.of(p);
    }

    /** 확장자를 그대로 믿지 않는다. 알려진 이미지 확장자만 통과시킨다 */
    static String extensionOf(String originalName) {
        if (originalName == null) return ".jpg";
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) return ".jpg";
        String ext = originalName.substring(dot).toLowerCase();
        return switch (ext) {
            case ".jpg", ".jpeg", ".png", ".webp" -> ext;
            default -> ".jpg";
        };
    }
}
