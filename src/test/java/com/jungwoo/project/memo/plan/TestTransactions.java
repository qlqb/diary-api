package com.jungwoo.project.memo.plan;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 단위 테스트용 TransactionTemplate. 실제 DB가 없으므로 경계만 흉내 낸다(commit/rollback은 아무것도 하지 않는다).
 * 트랜잭션 경계 자체는 실제 DB 통합 테스트(PlanDraftPersistenceDbTest)가 검증한다.
 */
final class TestTransactions {

    private TestTransactions() {
    }

    static TransactionTemplate template() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        });
    }
}
