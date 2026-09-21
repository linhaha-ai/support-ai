package com.haha.supportai.storage.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.haha.supportai.storage.FileStore;
import com.haha.supportai.storage.config.FileStorageProperties;
import com.haha.supportai.storage.exception.StorageDeleteException;
import com.haha.supportai.storage.exception.StorageKeyInvalidException;
import com.haha.supportai.storage.exception.StorageReadException;
import com.haha.supportai.storage.exception.StorageWriteException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class LocalFileStore implements FileStore {

    private static final Pattern EXTENSION_PATTERN = Pattern.compile("[a-z0-9]{1,16}");
    private static final Pattern STORAGE_KEY_PATTERN = Pattern.compile(
            "\\d{4}/\\d{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]{1,16}");

    private final FileStorageProperties fileStorageProperties;

    @Override
    public String store(InputStream inputStream, String extension) {
        if (inputStream == null) {
            throw new StorageWriteException(new IllegalArgumentException("上传内容不能为空"));
        }

        String normalizedExtension = normalizeExtension(extension);
        Path root = prepareRoot();
        LocalDate today = LocalDate.now();
        String storageKey = "%04d/%02d/%s.%s".formatted(today.getYear(), today.getMonthValue(),
                UUID.randomUUID(), normalizedExtension);
        Path target = resolveStoragePath(root, storageKey);
        Path temporaryFile = null;
        try {
            ensureTargetDirectory(root, target.getParent());
            Path temporaryDirectory = root.resolve(".tmp");
            Files.createDirectories(temporaryDirectory);
            temporaryFile = Files.createTempFile(temporaryDirectory, "upload-", ".tmp");
            Files.copy(inputStream, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
            moveToTarget(temporaryFile, target);
            return storageKey;
        } catch (IOException exception) {
            throw new StorageWriteException(exception);
        } finally {
            deleteTemporaryFile(temporaryFile);
        }
    }

    @Override
    public InputStream open(String storageKey) {
        Path root;
        try {
            root = prepareRoot();
        } catch (StorageWriteException exception) {
            throw new StorageReadException(exception);
        }
        Path target = resolveStoragePath(root, storageKey);
        try {
            Path actualTarget = target.toRealPath();
            if (!actualTarget.startsWith(root) || !Files.isRegularFile(actualTarget)) {
                throw new IOException("文件读取目标不合法");
            }
            return Files.newInputStream(actualTarget);
        } catch (IOException exception) {
            throw new StorageReadException(exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        Path root = prepareRoot();
        Path target = resolveStoragePath(root, storageKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new StorageDeleteException(exception);
        }
    }

    private Path prepareRoot() {
        try {
            Path configuredRoot = fileStorageProperties.getRoot();
            if (configuredRoot == null) {
                throw new StorageWriteException(new IllegalArgumentException("文件存储根目录不能为空"));
            }
            Path normalizedRoot = configuredRoot.toAbsolutePath().normalize();
            Files.createDirectories(normalizedRoot);
            return normalizedRoot.toRealPath();
        } catch (IOException exception) {
            throw new StorageWriteException(exception);
        }
    }

    private String normalizeExtension(String extension) {
        if (!StringUtils.hasText(extension)) {
            throw new StorageWriteException(new IllegalArgumentException("文件扩展名不能为空"));
        }
        String normalizedExtension = extension.trim().toLowerCase(Locale.ROOT);
        if (normalizedExtension.startsWith(".")) {
            normalizedExtension = normalizedExtension.substring(1);
        }
        if (!EXTENSION_PATTERN.matcher(normalizedExtension).matches()) {
            throw new StorageWriteException(new IllegalArgumentException("文件扩展名不合法"));
        }
        return normalizedExtension;
    }

    private Path resolveStoragePath(Path root, String storageKey) {
        if (!StringUtils.hasText(storageKey) || !STORAGE_KEY_PATTERN.matcher(storageKey).matches()) {
            throw new StorageKeyInvalidException();
        }
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new StorageKeyInvalidException();
        }
        return target;
    }

    private void ensureTargetDirectory(Path root, Path targetDirectory) throws IOException {
        Files.createDirectories(targetDirectory);
        Path actualTargetDirectory = targetDirectory.toRealPath();
        if (!actualTargetDirectory.startsWith(root)) {
            throw new StorageWriteException(new IllegalStateException("文件目标目录超出存储根目录"));
        }
    }

    private void moveToTarget(Path temporaryFile, Path target) throws IOException {
        try {
            Files.move(temporaryFile, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryFile, target);
        }
    }

    private void deleteTemporaryFile(Path temporaryFile) {
        if (temporaryFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException ignored) {
            // 临时文件清理由下一次上传或运维清理任务兜底，不能覆盖原始写入异常。
        }
    }
}
