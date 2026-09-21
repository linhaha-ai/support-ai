package com.haha.supportai.storage.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.haha.supportai.storage.config.FileStorageProperties;
import com.haha.supportai.storage.exception.StorageKeyInvalidException;
import com.haha.supportai.storage.exception.StorageWriteException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesContentWithServerGeneratedKey() throws IOException {
        LocalFileStore fileStore = fileStore();

        String storageKey = fileStore.store(streamOf("第一行\n第二行"), "md");

        assertThat(storageKey).matches("\\d{4}/\\d{2}/[0-9a-f-]{36}\\.md");
        assertThat(Files.readString(storageRoot().resolve(storageKey), StandardCharsets.UTF_8))
                .isEqualTo("第一行\n第二行");
    }

    @Test
    void generatesDifferentKeysForDifferentUploads() {
        LocalFileStore fileStore = fileStore();

        String firstKey = fileStore.store(streamOf("first"), "txt");
        String secondKey = fileStore.store(streamOf("second"), ".txt");

        assertThat(firstKey).isNotEqualTo(secondKey);
    }

    @Test
    void rejectsMaliciousKeyWithoutDeletingOutsideRoot() throws IOException {
        LocalFileStore fileStore = fileStore();
        Path outsideFile = temporaryDirectory.resolve("outside-file.txt");
        Files.writeString(outsideFile, "保留内容", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> fileStore.delete("../outside-file.txt"))
                .isInstanceOf(StorageKeyInvalidException.class);

        assertThat(Files.readString(outsideFile, StandardCharsets.UTF_8)).isEqualTo("保留内容");
    }

    @Test
    void deleteIsIdempotent() throws IOException {
        LocalFileStore fileStore = fileStore();
        String storageKey = fileStore.store(streamOf("待删除"), "txt");

        fileStore.delete(storageKey);
        fileStore.delete(storageKey);

        assertThat(Files.exists(storageRoot().resolve(storageKey))).isFalse();
    }

    @Test
    void clearsTemporaryFileWhenUploadStreamFails() throws IOException {
        LocalFileStore fileStore = fileStore();

        assertThatThrownBy(() -> fileStore.store(failingStream(), "txt"))
                .isInstanceOf(StorageWriteException.class);

        Path temporaryPath = storageRoot().resolve(".tmp");
        assertThat(Files.exists(temporaryPath)).isTrue();
        try (var files = Files.list(temporaryPath)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    private LocalFileStore fileStore() {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setRoot(storageRoot());
        return new LocalFileStore(properties);
    }

    private Path storageRoot() {
        return temporaryDirectory.resolve("storage");
    }

    private InputStream streamOf(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private InputStream failingStream() {
        return new InputStream() {
            private boolean firstRead = true;

            @Override
            public int read() throws IOException {
                if (firstRead) {
                    firstRead = false;
                    return 'x';
                }
                throw new IOException("模拟上传流失败");
            }
        };
    }
}
