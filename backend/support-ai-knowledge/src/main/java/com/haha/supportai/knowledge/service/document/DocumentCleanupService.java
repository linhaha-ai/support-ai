package com.haha.supportai.knowledge.service.document;

import java.util.List;

import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.knowledge.entity.dto.DocumentCleanupLease;
import com.haha.supportai.knowledge.exception.DocumentCleanupLeaseLostException;
import com.haha.supportai.storage.FileStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentCleanupService {

    private final DocumentCleanupPersistenceService persistenceService;
    private final DocumentVersionRepository documentVersionRepository;
    private final FileStore fileStore;

    public void executeOnce() {
        persistenceService.claim().ifPresent(this::execute);
    }

    private void execute(DocumentCleanupLease lease) {
        try {
            List<String> storageKeys = documentVersionRepository
                    .listStorageKeysForDeletedDocument(lease.getDocumentId());
            storageKeys.forEach(fileStore::delete);
            persistenceService.complete(lease);
        } catch (RuntimeException exception) {
            log.error("文档清理失败，等待自动重试：jobId={}, documentId={}, attempt={}, errorType={}",
                    lease.getJobId(), lease.getDocumentId(), lease.getAttempt(),
                    exception.getClass().getSimpleName());
            markRetry(lease);
        }
    }

    private void markRetry(DocumentCleanupLease lease) {
        try {
            persistenceService.retry(lease);
        } catch (DocumentCleanupLeaseLostException exception) {
            log.warn("文档清理失败回写被拒绝，租约已失效：jobId={}, documentId={}",
                    lease.getJobId(), lease.getDocumentId());
        }
    }
}
