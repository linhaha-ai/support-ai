package com.haha.supportai.knowledge.document.parsing.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.haha.supportai.data.knowledge.entity.po.DocumentVersion;
import com.haha.supportai.knowledge.document.config.DocumentUploadProperties;
import com.haha.supportai.knowledge.document.parsing.exception.DocumentReadFailedException;
import com.haha.supportai.storage.FileStore;
import com.haha.supportai.storage.exception.StorageKeyInvalidException;
import com.haha.supportai.storage.exception.StorageReadException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DocumentSourceReader {

    private static final int READ_BUFFER_SIZE = 8192;

    private final FileStore fileStore;
    private final DocumentUploadProperties uploadProperties;

    public String readVerifiedUtf8(DocumentVersion version) {
        long maxBytes = uploadProperties.getMaxFileSize().toBytes();
        if (version.getFileSize() < 0 || version.getFileSize() > maxBytes) {
            throw new DocumentReadFailedException(new IllegalStateException("文件大小与处理限制不一致"));
        }
        try (InputStream inputStream = fileStore.open(version.getStorageKey())) {
            byte[] content = readBounded(inputStream, maxBytes);
            if (content.length != version.getFileSize() || !sha256(content).equals(version.getFileHash())) {
                throw new DocumentReadFailedException(new IllegalStateException("原文件完整性校验失败"));
            }
            return decodeUtf8(content);
        } catch (StorageReadException | StorageKeyInvalidException | IOException exception) {
            throw new DocumentReadFailedException(exception);
        }
    }

    private byte[] readBounded(InputStream inputStream, long maxBytes) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        long totalBytes = 0;
        int readBytes;
        while ((readBytes = inputStream.read(buffer)) != -1) {
            totalBytes += readBytes;
            if (totalBytes > maxBytes) {
                throw new IOException("原文件超过处理大小限制");
            }
            outputStream.write(buffer, 0, readBytes);
        }
        return outputStream.toByteArray();
    }

    private String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new DocumentReadFailedException(exception);
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }
}
