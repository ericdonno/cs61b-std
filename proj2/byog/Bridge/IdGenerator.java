package byog.Bridge;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 协议层身份生成器接口：decisionId、messageId、messageSeq。
 */
public interface IdGenerator {

    /** 生成决策 ID，例 "decision-<n>" 或 UUID */
    String newDecisionId();

    /** 生成消息 ID，例 "msg-<n>" 或 UUID */
    String newMessageId();

    /** 生成消息序号，每 sessionEpoch 从 0 单调递增 */
    long nextMessageSeq();

    /** 生产实现：使用 java.util.UUID */
    final class UuidIdGenerator implements IdGenerator {
        private final AtomicLong messageSeq = new AtomicLong(0);

        @Override
        public String newDecisionId() {
            return "decision-" + UUID.randomUUID();
        }

        @Override
        public String newMessageId() {
            return "msg-" + UUID.randomUUID();
        }

        @Override
        public long nextMessageSeq() {
            return messageSeq.getAndIncrement();
        }
    }

    /** 测试实现：确定性计数器，构造时注入前缀，产生可重复的 ID */
    final class DeterministicIdGenerator implements IdGenerator {
        private final String decisionPrefix;
        private final String messagePrefix;
        private long decisionCounter = 0;
        private long messageCounter = 0;
        private long messageSeqCounter = 0;

        public DeterministicIdGenerator(String decisionPrefix, String messagePrefix) {
            this.decisionPrefix = decisionPrefix;
            this.messagePrefix = messagePrefix;
        }

        @Override
        public String newDecisionId() {
            return decisionPrefix + "-" + (decisionCounter++);
        }

        @Override
        public String newMessageId() {
            return messagePrefix + "-" + (messageCounter++);
        }

        @Override
        public long nextMessageSeq() {
            return messageSeqCounter++;
        }
    }
}
